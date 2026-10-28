package com.gabriel.android.sos

private var passed = 0
private val failed = mutableListOf<String>()
private fun test(n: String, b: () -> Unit) { try { b(); passed++; println("PASS  $n") } catch (t: Throwable) { failed += n; println("FAIL  $n -> $t") } }
private fun check(c: Boolean, m: String = "assertion failed") { if (!c) throw AssertionError(m) }
private inline fun <reified E : Throwable> throws(b: () -> Unit) {
    try { b() } catch (e: Throwable) { if (e is E) return; throw AssertionError("wrong exception $e") }
    throw AssertionError("expected ${E::class.simpleName}")
}
private fun id(seed: Int) = ByteArray(16) { (it + seed).toByte() }
private fun sos(seed: Int = 1, status: WireStatus = WireStatus.ACTIVE, loc: Boolean = true, msg: String = "help") =
    SosPayload(id(seed), EmergencyType.LOST, status, if (loc) 211_500_000 else null, if (loc) 790_800_000 else null,
        if (loc) 12 else null, if (loc) 5000L else null, 77, msg)

fun main() {
    val now = 10_000_000L
    test("payload round-trip with location") {
        val p = SosPayload.decode(sos(msg = "broken leg, near river").encode())
        check(p.hasLocation && p.latE7 == 211_500_000 && p.lonE7 == 790_800_000 && p.accuracyM == 12 && p.fixTimeMs == 5000L)
        check(p.batteryPct == 77 && p.message == "broken leg, near river" && p.type == EmergencyType.LOST && p.status == WireStatus.ACTIVE)
        check(p.eventIdHex == sos().eventIdHex)
    }
    test("payload without location / unknown battery / unknown accuracy") {
        val p = SosPayload.decode(sos(loc = false).withStatus(WireStatus.ACTIVE).let {
            SosPayload(it.eventId, it.type, it.status, null, null, null, null, null, "") }.encode())
        check(!p.hasLocation && p.batteryPct == null && p.latitude == null)
        val q = SosPayload.decode(SosPayload(id(2), EmergencyType.OTHER, WireStatus.ACTIVE, 1, 1, null, 1L, 50, "").encode())
        check(q.accuracyM == null)
    }
    test("utf-8 message and 140-byte limit") {
        check(SosPayload.decode(sos(msg = "मदद चाहिए").encode()).message == "मदद चाहिए")
        throws<IllegalArgumentException> { sos(msg = "x".repeat(141)) }
        sos(msg = "x".repeat(140))
    }
    test("constructor rejects bad coordinates / battery / partial location") {
        throws<IllegalArgumentException> { SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 910_000_000, 0, 1, 1L, 1, "") }
        throws<IllegalArgumentException> { SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, 1_900_000_000, 1, 1L, 1, "") }
        throws<IllegalArgumentException> { SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, 0, 1, 1L, 101, "") }
        throws<IllegalArgumentException> { SosPayload(id(1), EmergencyType.LOST, WireStatus.ACTIVE, 0, null, 1, 1L, 1, "") }
    }
    test("decode rejects truncated, trailing, bad version/type/status/flags, bad coords, bad battery") {
        val e = sos().encode()
        for (n in 0 until e.size) throws<SosFormatException> { SosPayload.decode(e.copyOf(n)) }
        throws<SosFormatException> { SosPayload.decode(e + byteArrayOf(0)) }
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[0] = 2 }) }
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[17] = 99 }) }
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[18] = 99 }) }
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[19] = 0x81.toByte() }) }
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[20] = 0x7f; it[21] = 0xff.toByte() }) } // lat huge
        throws<SosFormatException> { SosPayload.decode(e.clone().also { it[38] = 150.toByte() }) } // battery byte
    }
    test("fuzz: 20000 random inputs never throw anything but SosFormatException") {
        val r = java.util.Random(7)
        repeat(20000) {
            val g = ByteArray(r.nextInt(80)).also { r.nextBytes(it) }
            if (g.isNotEmpty() && r.nextBoolean()) g[0] = 1
            try { SosPayload.decode(g) } catch (_: SosFormatException) {}
        }
    }
    test("ack payload round-trip and rejection") {
        check(SosAckPayload.decode(SosAckPayload.encode(id(3))).contentEquals(id(3)))
        throws<SosFormatException> { SosAckPayload.decode(ByteArray(5)) }
        throws<SosFormatException> { SosAckPayload.decode(byteArrayOf(2) + id(3)) }
    }
    test("inbox: stores new event, newer update wins, older ignored (replay)") {
        val i = SosInbox()
        check(i.apply(sos(), "peerA", now - 1000, now))
        check(!i.apply(sos(), "peerA", now - 2000, now))
        check(i.apply(sos(msg = "update"), "peerA", now - 500, now) && i.entries.single().payload.message == "update")
    }
    test("inbox: resolved stays resolved even if stale ACTIVE is replayed later") {
        val i = SosInbox()
        i.apply(sos(), "peerA", now - 3000, now)
        i.apply(sos(status = WireStatus.RESOLVED), "peerA", now - 2000, now)
        check(!i.apply(sos(), "peerA", now - 1000, now))   // newer timestamp, but ended events cannot reopen
        check(i.entries.single().payload.status == WireStatus.RESOLVED)
    }
    test("inbox: same eventId from a different authenticated origin is a separate event (no overwrite)") {
        val i = SosInbox()
        i.apply(sos(msg = "real"), "peerA", now - 1000, now)
        i.apply(sos(msg = "fake"), "peerM", now - 500, now)
        check(i.entries.size == 2 && i.entries.first { it.originPeerID == "peerA" }.payload.message == "real")
    }
    test("inbox: rejects stale (>24h) and future (>5min) packets") {
        val i = SosInbox()
        check(!i.apply(sos(), "p", now - 25 * 3600_000L, now))
        check(!i.apply(sos(), "p", now + 10 * 60_000L, now))
        check(i.entries.isEmpty())
    }
    test("inbox: bounded at capacity") {
        val i = SosInbox(capacity = 5)
        for (k in 0 until 20) i.apply(sos(seed = k), "p", now - 100, now + k)
        check(i.entries.size == 5)
    }
    test("sender state: forward-only, truthful ack rules") {
        val e = SosEvent("e1", 0)
        e.markQueued(); check(e.status == SosStatus.QUEUED)
        e.markHandedToPeer(); check(e.status == SosStatus.RELAYED)
        check(!e.applyAck("e1", "f", false, 1) && !e.applyAck("zz", "f", true, 1) && e.status == SosStatus.RELAYED)
        check(e.applyAck("e1", "f", true, 1) && e.status == SosStatus.ACKNOWLEDGED)
        e.markQueued(); e.markHandedToPeer(); check(e.status == SosStatus.ACKNOWLEDGED)
        e.cancel(); check(e.status == SosStatus.CANCELLED && !e.applyAck("e1", "f", true, 2))
    }
    println("\n$passed passed, ${failed.size} failed")
    if (failed.isNotEmpty()) { println("FAILED: $failed"); System.exit(1) }
}
