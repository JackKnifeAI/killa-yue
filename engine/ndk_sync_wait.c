/* sync_wait for building Turnip with the Android NDK.
 * The NDK's libsync.so exports only the public sync API, while Turnip also calls
 * sync_wait (present in the phone's own libsync, which Termux's build linked to).
 * This is the same poll()-based wait as AOSP's libsync (Apache-2.0), kept hidden
 * so it never interposes on the system library. Not needed for the Termux build. */
#include <errno.h>
#include <poll.h>

__attribute__((visibility("hidden"))) int sync_wait(int fd, int timeout)
{
    struct pollfd fds;
    int ret;

    if (fd < 0) {
        errno = EINVAL;
        return -1;
    }
    fds.fd = fd;
    fds.events = POLLIN;
    do {
        ret = poll(&fds, 1, timeout);
        if (ret > 0) {
            if (fds.revents & (POLLERR | POLLNVAL)) {
                errno = EINVAL;
                return -1;
            }
            return 0;
        } else if (ret == 0) {
            errno = ETIME;
            return -1;
        }
    } while (ret == -1 && (errno == EINTR || errno == EAGAIN));
    return ret;
}
