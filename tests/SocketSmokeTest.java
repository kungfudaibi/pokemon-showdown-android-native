package dev.local.showdownnative;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SocketSmokeTest {
    public static void main(String[] args) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        ShowdownSocket client = new ShowdownSocket(new ShowdownSocket.Listener() {
            @Override public void onOpen() { ready.countDown(); }
            @Override public void onMessage(String message) {
                if (message.startsWith("|updateuser|")) ready.countDown();
            }
            @Override public void onClosed(String reason) { }
        });
        client.connect();
        boolean success = ready.await(15, TimeUnit.SECONDS);
        if (success) client.send("|/query roomlist gen9randombattle");
        client.close();
        if (!success) throw new AssertionError("Showdown did not send a guest session");
        System.out.println("WebSocket handshake and guest session passed");
    }
}
