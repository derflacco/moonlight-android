#pragma once
#include <stdint.h>
#include <stddef.h>
#include "spsc_ring.h"
#ifdef __cplusplus
extern "C" { 
#endif
typedef struct {
    int fd;
    volatile int* stopping;
    spsc_ring_t* ring;    // if non-null, push here
    int max_batch;        // e.g., 16
    int max_udp;          // e.g., 2048
    int rcvbuf_bytes;     // e.g., 8*1024*1024
    int micro_backoff_ns; // e.g., 200000
} rx_batch_cfg_t;
void rx_batch_loop(const rx_batch_cfg_t* cfg);
#ifdef __cplusplus
}
#endif
