#include "spsc_ring.h"
#include <stdlib.h>
int spsc_ring_init(spsc_ring_t* r, uint32_t cap) {
    if (!r) return -1;
    if ((cap & (cap - 1)) != 0) return -2;
    r->cap = cap; r->mask = cap - 1; r->head = 0; r->tail = 0;
    r->entries = (spsc_pkt_t*)calloc(cap, sizeof(spsc_pkt_t));
    return r->entries ? 0 : -3;
}
void spsc_ring_free(spsc_ring_t* r) {
    if (!r) return;
    free(r->entries); r->entries = NULL; r->cap = r->mask = 0; r->head = r->tail = 0;
}
int spsc_ring_offer(spsc_ring_t* r, spsc_pkt_t v) {
    uint32_t h = __atomic_load_n(&r->head, __ATOMIC_RELAXED);
    uint32_t n = (h + 1) & r->mask;
    uint32_t t = __atomic_load_n(&r->tail, __ATOMIC_ACQUIRE);
    if (n == t) return 0;
    r->entries[h] = v;
    __atomic_store_n(&r->head, n, __ATOMIC_RELEASE);
    return 1;
}
int spsc_ring_poll(spsc_ring_t* r, spsc_pkt_t* out) {
    uint32_t t = __atomic_load_n(&r->tail, __ATOMIC_RELAXED);
    uint32_t h = __atomic_load_n(&r->head, __ATOMIC_ACQUIRE);
    if (t == h) return 0;
    *out = r->entries[t];
    r->entries[t].data = NULL; r->entries[t].len = 0;
    __atomic_store_n(&r->tail, (t + 1) & r->mask, __ATOMIC_RELEASE);
    return 1;
}
