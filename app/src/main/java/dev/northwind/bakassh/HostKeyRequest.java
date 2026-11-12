package dev.northwind.bakassh;

import java.util.concurrent.CountDownLatch;

/** A service-owned request survives Activity replacement; cancellation cannot later become approval. */
final class HostKeyRequest {
    final HostKeyTrust.Challenge challenge;
    private final CountDownLatch answered = new CountDownLatch(1);
    private boolean complete, trusted;
    HostKeyRequest(HostKeyTrust.Challenge challenge) { this.challenge = challenge; }
    synchronized void answer(boolean trusted) {
        if (complete) return;
        complete = true;
        this.trusted = trusted;
        answered.countDown();
    }
    boolean await() throws InterruptedException {
        try { answered.await(); synchronized (this) { return trusted; } }
        catch (InterruptedException error) { answer(false); throw error; }
    }
    synchronized boolean pending() { return !complete; }
}
