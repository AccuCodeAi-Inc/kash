package com.accucodeai.kash.interpreter

/**
 * Replicates GNU bash's associative-array iteration order.
 *
 * Bash uses FNV-1 string hashing seeded with `FNV_OFFSET = 2166136261`,
 * and `declare -A` / `typeset -A` create the underlying hash table with
 * [ASSOC_HASH_BUCKETS] = 1024 buckets. Within each bucket, new entries are
 * inserted at the head of the linked list — most-recently-added wins.
 * Buckets walked 0..1023.
 *
 * This isn't bash's only ordering rule (rehash on growth shuffles things), but
 * for small arrays (well under the 2048-entry rehash threshold) it matches
 * the upstream test fixtures.
 */
internal object BashAssocOrder {
    private const val FNV_OFFSET: UInt = 2166136261u
    private const val FNV_PRIME: UInt = 16777619u
    private const val BUCKETS = 1024

    /**
     * Bucket count for the command-hash table that backs `BASH_CMDS` / the
     * `hash` builtin. Bash sizes this table at 256 buckets, distinct from
     * the generic `declare -A` table's [BUCKETS].
     */
    private const val CMD_HASH_BUCKETS = 256

    /**
     * Bucket count for the alias table that backs `BASH_ALIASES`. Bash sizes
     * the alias table at 64 buckets.
     */
    private const val ALIAS_HASH_BUCKETS = 64

    fun hash(key: String): UInt {
        var i = FNV_OFFSET
        for (c in key) {
            i *= FNV_PRIME
            i = i xor c.code.toUInt()
        }
        return i
    }

    /** Reorder [insertionOrderKeys] (oldest first) the way bash's hash table would. */
    fun order(insertionOrderKeys: Iterable<String>): List<String> {
        val mask = BUCKETS - 1
        val byBucket = HashMap<Int, ArrayDeque<String>>()
        for (k in insertionOrderKeys) {
            val b = hash(k).toInt() and mask
            byBucket.getOrPut(b) { ArrayDeque() }.addFirst(k)
        }
        val out = ArrayList<String>()
        for (b in byBucket.keys.sorted()) out.addAll(byBucket.getValue(b))
        return out
    }

    /**
     * Iteration order for the command-hash array (`BASH_CMDS`, `hash`).
     * Bash builds the visible assoc by walking the 256-bucket filename hash
     * table and re-inserting each entry into a second 256-bucket assoc; the
     * double pass yields bucket-ascending order
     * with insertion (FIFO) order within a bucket — unlike the generic
     * single-table [order], which is LIFO within a bucket.
     */
    fun commandHashOrder(insertionOrderKeys: Iterable<String>): List<String> =
        tableOrder(insertionOrderKeys, CMD_HASH_BUCKETS)

    /**
     * Iteration order for `BASH_ALIASES`, backed by bash's 64-bucket alias
     * table. Same bucket-ascending / FIFO-within-bucket shape as
     * [commandHashOrder], just a smaller table.
     */
    fun aliasHashOrder(insertionOrderKeys: Iterable<String>): List<String> =
        tableOrder(insertionOrderKeys, ALIAS_HASH_BUCKETS)

    /** Bucket-ascending walk of [keys] hashed into [buckets] slots, FIFO
     *  within each bucket — models bash's "walk source table, re-insert into
     *  same-size visible assoc" build for the internal-table-backed arrays. */
    private fun tableOrder(
        keys: Iterable<String>,
        buckets: Int,
    ): List<String> {
        val mask = buckets - 1
        val byBucket = HashMap<Int, ArrayList<String>>()
        for (k in keys) {
            val b = hash(k).toInt() and mask
            byBucket.getOrPut(b) { ArrayList() }.add(k)
        }
        val out = ArrayList<String>()
        for (b in byBucket.keys.sorted()) out.addAll(byBucket.getValue(b))
        return out
    }
}
