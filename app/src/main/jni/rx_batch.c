#define _GNU_SOURCE
#include "rx_batch.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#ifndef MSG_WAITFORONE
#define MSG_WAITFORONE 0x10000
#endif
static int set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}
void rx_batch_loop(const rx_batch_cfg_t* cfg) {
    if (!cfg || cfg->fd < 0) return;
    const int B = cfg->max_batch > 0 ? cfg->max_batch : 16;
    const int M = cfg->max_udp  > 0 ? cfg->max_udp  : 2048;
    const int backoff_ns = cfg->micro_backoff_ns > 0 ? cfg->micro_backoff_ns : 200000;
    struct iovec* iov = (struct iovec*)calloc(B, sizeof(struct iovec));
    struct mmsghdr* msgs = (struct mmsghdr*)calloc(B, sizeof(struct mmsghdr));
    uint8_t* bufs = (uint8_t*)malloc((size_t)B * (size_t)M);
    if (!iov || !msgs || !bufs) { free(iov); free(msgs); free(bufs); return; }
    for (int i=0;i<B;i++){ iov[i].iov_base = bufs + (size_t)i*(size_t)M; iov[i].iov_len=M; memset(&msgs[i],0,sizeof(msgs[i])); msgs[i].msg_hdr.msg_iov=&iov[i]; msgs[i].msg_hdr.msg_iovlen=1; }
    set_nonblock(cfg->fd);
    if (cfg->rcvbuf_bytes>0){ int v=cfg->rcvbuf_bytes; setsockopt(cfg->fd,SOL_SOCKET,SO_RCVBUF,&v,sizeof(v)); }
    struct timespec ts={0, backoff_ns};
    while (!*cfg->stopping) {
        int n = recvmmsg(cfg->fd, msgs, B, MSG_DONTWAIT | MSG_WAITFORONE, NULL);
        if (n <= 0) {
            if (errno==EAGAIN || errno==EWOULDBLOCK || errno==EINTR) { nanosleep(&ts,NULL); continue; }
            nanosleep(&ts,NULL); continue;
        }
        for (int i=0;i<n;i++) {
            size_t len = msgs[i].msg_len;
            if (len==0) continue;
            if (cfg->ring) {
                spsc_pkt_t p = { .data = (uint8_t*)iov[i].iov_base, .len = (uint32_t)len };
                if (!spsc_ring_offer(cfg->ring, p)) { /* drop on overflow */ }
            }
        }
    }
    free(bufs); free(iov); free(msgs);
}
