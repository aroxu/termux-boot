#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef CLONE_NEWCGROUP
#define CLONE_NEWCGROUP 0x02000000
#endif

static const char *const kAllowedRoot = "/data/local/debian";
static volatile sig_atomic_t child_pid = -1;

static int fail_errno(const char *stage, int code) {
    const int saved_errno = errno;
    fprintf(stderr,
            "BFU_DEBIAN_NAMESPACE_FAILED stage=%s errno=%d error=%s\n",
            stage, saved_errno, strerror(saved_errno));
    return code;
}

static int fail_message(const char *stage, const char *message, int code) {
    fprintf(stderr, "BFU_DEBIAN_NAMESPACE_FAILED stage=%s error=%s\n",
            stage, message);
    return code;
}

static void alarm_handler(int signal_number) {
    (void) signal_number;
    if (child_pid > 0) kill((pid_t) child_pid, SIGKILL);
    _exit(124);
}

static bool is_directory(const char *path) {
    struct stat value;
    return lstat(path, &value) == 0 && S_ISDIR(value.st_mode);
}

static bool is_regular_executable(const char *path) {
    struct stat value;
    // Debian /bin/sh is normally a symlink to dash; validate its resolved file.
    return stat(path, &value) == 0 && S_ISREG(value.st_mode)
            && access(path, R_OK | X_OK) == 0;
}

static int root_path(char *output, size_t output_size, const char *root,
                     const char *relative) {
    const int count = snprintf(output, output_size, "%s/%s", root, relative);
    if (count < 0 || (size_t) count >= output_size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static int validate_rootfs(const char *root) {
    char resolved[PATH_MAX];
    char path[PATH_MAX];
    struct stat root_stat;

    if (strcmp(root, kAllowedRoot) != 0) {
        return fail_message("root_not_allowed", "only_/data/local/debian_is_allowed", 20);
    }
    if (lstat(root, &root_stat) != 0) return fail_errno("root_lstat", 21);
    if (!S_ISDIR(root_stat.st_mode)) {
        return fail_message("root_not_directory", "rootfs_is_not_a_directory", 22);
    }
    if (root_stat.st_uid != 0) {
        return fail_message("root_not_owned_by_uid_0", "unsafe_rootfs_owner", 23);
    }
    if ((root_stat.st_mode & (S_IWGRP | S_IWOTH)) != 0) {
        return fail_message("root_group_or_world_writable", "unsafe_rootfs_mode", 23);
    }
    if (realpath(root, resolved) == NULL) return fail_errno("root_realpath", 24);
    if (strcmp(resolved, kAllowedRoot) != 0) {
        return fail_message("root_resolved_elsewhere", "rootfs_symlink_is_forbidden", 25);
    }

    if (root_path(path, sizeof(path), root, ".termux-bfu-rootfs") != 0
            || access(path, R_OK) != 0) {
        return fail_errno("rootfs_marker", 26);
    }
    if (root_path(path, sizeof(path), root, "bin/sh") != 0
            || !is_regular_executable(path)) {
        return fail_message("debian_shell", "missing_readable_executable_bin_sh", 27);
    }

    const char *const directories[] = {"dev", "proc", "sys", "run"};
    const size_t directory_count = sizeof(directories) / sizeof(directories[0]);
    for (size_t index = 0; index < directory_count; index++) {
        if (root_path(path, sizeof(path), root, directories[index]) != 0) {
            return fail_errno("rootfs_mount_path", 28);
        }
        if (!is_directory(path)) {
            return fail_message("rootfs_mount_directory",
                                "required_mount_directory_is_missing", 29);
        }
    }
    return 0;
}

static int bind_recursively(const char *source, const char *target,
                            const char *bind_stage, const char *slave_stage) {
    if (mount(source, target, NULL, MS_BIND | MS_REC, NULL) != 0) {
        return fail_errno(bind_stage, 40);
    }
    if (mount(NULL, target, NULL, MS_SLAVE | MS_REC, NULL) != 0) {
        return fail_errno(slave_stage, 41);
    }
    return 0;
}

static int prepare_child_mounts(const char *root) {
    char path[PATH_MAX];
    int result;

    if (root_path(path, sizeof(path), root, "dev") != 0) {
        return fail_errno("dev_path", 42);
    }
    result = bind_recursively("/dev", path, "dev_rbind", "dev_make_rslave");
    if (result != 0) return result;

    if (root_path(path, sizeof(path), root, "sys") != 0) {
        return fail_errno("sys_path", 43);
    }
    result = bind_recursively("/sys", path, "sys_rbind", "sys_make_rslave");
    if (result != 0) return result;

    if (root_path(path, sizeof(path), root, "proc") != 0) {
        return fail_errno("proc_path", 44);
    }
    if (mount("proc", path, "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0) {
        return fail_errno("proc_mount", 45);
    }

    if (root_path(path, sizeof(path), root, "run") != 0) {
        return fail_errno("run_path", 46);
    }
    if (mount("tmpfs", path, "tmpfs", MS_NOSUID | MS_NODEV,
              "mode=0755,size=16m") != 0) {
        return fail_errno("run_tmpfs", 47);
    }
    if (root_path(path, sizeof(path), root, "run/lock") != 0) {
        return fail_errno("run_lock_path", 48);
    }
    if (mkdir(path, 0755) != 0 && errno != EEXIST) {
        return fail_errno("run_lock_mkdir", 49);
    }
    return 0;
}

static int enter_debian_probe(const char *root) {
    static const char probe_command[] =
            "set -u; "
            "fail() { printf 'BFU_DEBIAN_NAMESPACE_FAILED stage=%s\\n' \"$1\"; exit 60; }; "
            "[ \"$$\" -eq 1 ] || fail debian_shell_not_pid1; "
            "IFS= read -r proc1 < /proc/1/comm || fail proc1_read; "
            "[ \"$proc1\" = sh ] || fail proc1_not_debian_shell; "
            "arch=$(/usr/bin/dpkg --print-architecture) || fail dpkg_arch; "
            "[ \"$arch\" = arm64 ] || fail architecture_not_arm64; "
            "version=$(/usr/bin/cut -d. -f1 /etc/debian_version) || fail debian_version_read; "
            "[ \"$version\" = 13 ] || fail debian_version_not_13; "
            "if [ -x /sbin/init ]; then init=present; else init=absent; fi; "
            "if [ -x /usr/bin/systemctl ]; then systemctl=present; else systemctl=absent; fi; "
            "cgroup=$(while IFS= read -r line; do printf '%s,' \"$line\"; done < /proc/self/cgroup); "
            "printf 'BFU_DEBIAN_NAMESPACE_OK pid=%s proc1=%s arch=%s debian=%s init=%s systemctl=%s cgroup=%s\\n' "
            "\"$$\" \"$proc1\" \"$arch\" \"$version\" \"$init\" \"$systemctl\" \"$cgroup\"";

    int result = prepare_child_mounts(root);
    if (result != 0) return result;
    if (syscall(__NR_sethostname, "termux-bfu-probe",
                strlen("termux-bfu-probe")) != 0) {
        return fail_errno("sethostname", 50);
    }
    if (chdir(root) != 0) return fail_errno("chdir_rootfs", 51);
    if (chroot(".") != 0) return fail_errno("chroot", 52);
    if (chdir("/") != 0) return fail_errno("chdir_chroot", 53);

    clearenv();
    setenv("HOME", "/root", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("container", "termux-bfu", 1);

    char *const arguments[] = {"sh", "-c", (char *) probe_command, NULL};
    execv("/bin/sh", arguments);
    return fail_errno("exec_debian_shell", 54);
}

static int run_probe(const char *root) {
    int result = validate_rootfs(root);
    if (result != 0) return result;
    if (geteuid() != 0) {
        return fail_message("not_root", "launcher_requires_euid_0", 30);
    }

    signal(SIGALRM, alarm_handler);
    alarm(25);

    if (unshare(CLONE_NEWNS) != 0) return fail_errno("unshare_mount", 31);
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        return fail_errno("mount_make_rprivate", 32);
    }
    if (unshare(CLONE_NEWUTS) != 0) return fail_errno("unshare_uts", 33);
    if (unshare(CLONE_NEWIPC) != 0) return fail_errno("unshare_ipc", 34);
    if (unshare(CLONE_NEWCGROUP) != 0) return fail_errno("unshare_cgroup", 35);
    if (unshare(CLONE_NEWPID) != 0) return fail_errno("unshare_pid", 36);

    const pid_t pid = fork();
    if (pid < 0) return fail_errno("fork_pid1", 37);
    if (pid == 0) {
        child_pid = -1;
        signal(SIGALRM, alarm_handler);
        alarm(20);
        _exit(enter_debian_probe(root));
    }

    child_pid = pid;
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        return fail_errno("wait_pid1", 38);
    }
    child_pid = -1;
    alarm(0);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) {
        char message[64];
        snprintf(message, sizeof(message), "pid1_killed_by_signal_%d", WTERMSIG(status));
        return fail_message("pid1_signal", message, 39);
    }
    return fail_message("pid1_wait_status", "unexpected_wait_status", 39);
}

int main(int argc, char **argv) {
    if (argc != 3 || strcmp(argv[1], "probe") != 0) {
        fprintf(stderr, "usage: %s probe /data/local/debian\n", argv[0]);
        return 2;
    }
    return run_probe(argv[2]);
}
