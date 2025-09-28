#pragma once
#include <stdint.h>
#include <stdatomic.h>
typedef struct { uint8_t* data; uint32_t len; } spsc_pkt_t;
typedef struct {
    uint32_t cap, mask;
    _Atomic uint32_t head, tail;
    spsc_pkt_t* entries;
} spsc_ring_t;
int  spsc_ring_init(spsc_ring_t* r, uint32_t capacity_pow2);
void spsc_ring_free(spsc_ring_t* r);
int  spsc_ring_offer(spsc_ring_t* r, spsc_pkt_t v);
int  spsc_ring_poll(spsc_ring_t* r, spsc_pkt_t* out);
