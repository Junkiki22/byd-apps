package com.wheregoes.carha;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Minimal ADB client that talks to the car's own adbd on localhost:5555 and runs
 * one shell command. autoservice (odometer, SOH, BMS kWh...) only answers the
 * shell uid, so this is the one way an ordinary app can reach those values.
 *
 * The RSA key is generated once and kept in the app's files dir. The first
 * connection shows DiLink's "Allow USB debugging?" dialog; after "always allow"
 * it is silent. The public key is offered at most once per process so an
 * unanswered dialog is not re-raised every poll.
 */
final class AdbShell {

    private static final String TAG = "CarHaAdb";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    /** How long to wait for the user to tap "allow" after offering the key. */
    private static final int AUTH_WAIT_MS = 60_000;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** DER DigestInfo prefix for SHA-1; adbd verifies the token as a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static volatile boolean sKeyOffered = false;
    /** Last outcome, surfaced in the UI. */
    static volatile String sLastStatus = "(未接続)";

    private final File keyDir;
    private KeyPair keys;

    AdbShell(Context context) {
        this.keyDir = context.getApplicationContext().getFilesDir();
    }

    /** Run {@code cmd} via shell and return its stdout, or null on any failure. Never throws. */
    String run(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            if (!handshake(s, in, out)) {
                return null;
            }
            String result = shell(in, out, cmd);
            sLastStatus = result == null ? "シェル失敗" : "OK";
            return result;
        } catch (Exception e) {
            sLastStatus = "ERR: " + e.getClass().getSimpleName() + " " + e.getMessage();
            Log.d(TAG, "adb run failed: " + e);
            return null;
        }
    }

    // -------------------------------------------------------------- handshake

    private boolean handshake(Socket s, DataInputStream in, OutputStream out) throws Exception {
        send(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Msg m = recv(in);
            if (m.cmd == A_CNXN) {
                return true;
            }
            if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) {
                sLastStatus = "想定外の応答";
                return false;
            }
            if (!signed) {
                signed = true;
                send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                continue;
            }
            // Signature rejected: this key is not yet trusted.
            if (sKeyOffered) {
                sLastStatus = "未許可（車の画面で許可してください）";
                return false;
            }
            sKeyOffered = true;
            send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload());
            sLastStatus = "許可待ち（車の画面で「常に許可」）";
            s.setSoTimeout(AUTH_WAIT_MS);
            Msg r = recv(in);
            s.setSoTimeout(READ_TIMEOUT_MS);
            if (r.cmd == A_CNXN) {
                return true;
            }
            sLastStatus = "未許可";
            return false;
        }
    }

    // ------------------------------------------------------------------ shell

    private String shell(DataInputStream in, OutputStream out, String cmd) throws IOException {
        final int localId = 1;
        send(out, A_OPEN, localId, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Msg m = recv(in);
            if (m.cmd == A_OKAY) {
                remoteId = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remoteId = m.arg0;
                buf.write(m.data);
                send(out, A_OKAY, localId, remoteId, null);
            } else if (m.cmd == A_CLSE) {
                send(out, A_CLSE, localId, m.arg0, null);
                return buf.toString("UTF-8");
            }
        }
    }

    // --------------------------------------------------------------- framing

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data)
            throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xff;
        }
        ByteBuffer h = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) {
            h.put(data);
        }
        out.write(h.array());
        out.flush();
    }

    private static Msg recv(DataInputStream in) throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------------- keys

    private byte[] sign(byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, keys().getPrivate());
        c.update(SHA1_DIGEST_INFO);
        return c.doFinal(token);
    }

    /** Android's mincrypt RSAPublicKey struct, base64'd, plus a key name. */
    private byte[] publicKeyPayload() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) keys().getPublic();
        BigInteger n = pub.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        putLittleEndianWords(b, n, words);
        putLittleEndianWords(b, rr, words);
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " car-ha@dolce\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putLittleEndianWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) {
            b.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private synchronized KeyPair keys() throws Exception {
        if (keys != null) {
            return keys;
        }
        File priv = new File(keyDir, "adbkey.pk8");
        File pub = new File(keyDir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
            RSAPublicKey pu = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readAll(pub)));
            keys = new KeyPair(pu, pk);
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            keys = g.generateKeyPair();
            writeAll(priv, keys.getPrivate().getEncoded());
            writeAll(pub, keys.getPublic().getEncoded());
            Log.i(TAG, "generated new adb key");
        }
        return keys;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }
}
