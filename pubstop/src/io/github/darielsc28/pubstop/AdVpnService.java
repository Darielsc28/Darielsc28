package io.github.darielsc28.pubstop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VPN local : seul le trafic DNS passe par l'interface. Les domaines publicitaires
 * reçoivent une réponse nulle, le reste est relayé au DNS habituel du réseau.
 * Aucune donnée de navigation ne quitte l'appareil par un autre chemin que d'habitude.
 */
public class AdVpnService extends VpnService implements Runnable {
    static final String ACTION_START = "io.github.darielsc28.pubstop.START";
    static final String ACTION_STOP = "io.github.darielsc28.pubstop.STOP";
    static final String ACTION_RELOAD = "io.github.darielsc28.pubstop.RELOAD";
    static final String HOSTS_FILE = "hosts.txt";

    private static final String TAG = "PubStop";
    private static final String CHANNEL = "vpn";
    private static final String VPN_ADDRESS = "10.111.222.1";
    private static final String VPN_DNS = "10.111.222.2";
    private static final String[] FALLBACK_DNS = {"1.1.1.1", "9.9.9.9"};
    private static final int MTU = 4096;

    // État partagé avec l'interface.
    static volatile boolean running;
    static volatile int listSize;
    static final AtomicLong blockedCount = new AtomicLong();
    static final AtomicLong totalCount = new AtomicLong();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();

    private volatile Blocklist blocklist;
    private volatile boolean stopRequested;
    private volatile Thread thread;
    private FileDescriptor wakeWrite;
    private FileOutputStream tunOut;
    private ThreadPoolExecutor pool;

    /** Serveur DNS amont, éventuellement lié à un réseau précis (Wi-Fi, mobile). */
    private static final class Upstream {
        final Network network;
        final InetAddress address;
        Upstream(Network network, InetAddress address) {
            this.network = network;
            this.address = address;
        }
    }
    private volatile List<Upstream> upstreams;
    private volatile long upstreamsAt;
    private volatile Upstream lastGood;

    static List<String> recentBlocked() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private static void remember(String name) {
        synchronized (recent) {
            recent.remove(name);
            recent.addFirst(name);
            while (recent.size() > 40) recent.removeLast();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            if (thread != null) new Thread(this::loadBlocklist, "pubstop-reload").start();
            else stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(1, buildNotification());
        } catch (RuntimeException e) {
            // Redémarrage par le système en arrière-plan : la notification peut être refusée,
            // mais le VPN reste maintenu en vie par le système tant qu'il est établi.
            Log.w(TAG, "Notification de premier plan refusée", e);
        }
        Thread old = thread;
        if (old != null && stopRequested) {
            // Arrêt en cours : on attend la fin de l'ancien tunnel avant d'en rouvrir un.
            try {
                old.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        if (thread == null) {
            stopRequested = false;
            running = true;
            thread = new Thread(this, "pubstop-vpn");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        shutdown();
    }

    @Override
    public void onDestroy() {
        requestStop();
        super.onDestroy();
    }

    private void shutdown() {
        requestStop();
        stopForeground(true);
        stopSelf();
    }

    private synchronized void requestStop() {
        stopRequested = true;
        running = false;
        if (wakeWrite != null) {
            try {
                Os.write(wakeWrite, new byte[] {1}, 0, 1);
            } catch (Exception ignored) {
            }
        }
    }

    private void loadBlocklist() {
        try {
            File f = new File(getFilesDir(), HOSTS_FILE);
            Blocklist b;
            try (InputStream in = f.exists() ? new FileInputStream(f) : getAssets().open(HOSTS_FILE)) {
                b = Blocklist.load(in);
            }
            if (b.size() < 1000 && f.exists()) {
                // Fichier téléchargé inutilisable : retour à la liste embarquée.
                try (InputStream in = getAssets().open(HOSTS_FILE)) {
                    b = Blocklist.load(in);
                }
            }
            blocklist = b;
            listSize = b.size();
        } catch (IOException e) {
            Log.e(TAG, "Chargement de la liste impossible", e);
        }
    }

    @Override
    public void run() {
        ParcelFileDescriptor tun = null;
        FileDescriptor wakeRead = null;
        try {
            loadBlocklist();
            if (blocklist == null) return;

            Builder b = new Builder()
                    .setSession("PubStop")
                    .addAddress(VPN_ADDRESS, 24)
                    .addDnsServer(VPN_DNS)
                    .addRoute(VPN_DNS, 32)
                    .setMtu(MTU)
                    .setConfigureIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            tun = b.establish();
            if (tun == null) {
                Log.e(TAG, "Autorisation VPN absente");
                return;
            }

            FileDescriptor[] pipe = Os.pipe();
            wakeRead = pipe[0];
            synchronized (this) {
                wakeWrite = pipe[1];
            }
            FileDescriptor tunFd = tun.getFileDescriptor();
            tunOut = new FileOutputStream(tunFd);
            pool = new ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(256), new ThreadPoolExecutor.DiscardOldestPolicy());
            pool.allowCoreThreadTimeOut(true);

            StructPollfd pTun = new StructPollfd();
            pTun.fd = tunFd;
            pTun.events = (short) OsConstants.POLLIN;
            StructPollfd pWake = new StructPollfd();
            pWake.fd = wakeRead;
            pWake.events = (short) OsConstants.POLLIN;
            StructPollfd[] fds = {pTun, pWake};
            byte[] buf = new byte[MTU + 64];

            while (!stopRequested) {
                try {
                    Os.poll(fds, -1);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (pWake.revents != 0) break;
                if ((pTun.revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0) break;
                if ((pTun.revents & OsConstants.POLLIN) == 0) continue;
                // Un seul paquet par réveil : poll() revient aussitôt s'il en reste,
                // et on ne dépend pas du mode (bloquant ou non) du descripteur.
                int n;
                try {
                    n = Os.read(tunFd, buf, 0, buf.length);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue;
                    throw e;
                }
                if (n > 0) handlePacket(buf, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Arrêt du VPN sur erreur", e);
        } finally {
            running = false;
            if (pool != null) pool.shutdownNow();
            synchronized (this) {
                closeQuietly(wakeWrite);
                wakeWrite = null;
            }
            closeQuietly(wakeRead);
            if (tun != null) {
                try {
                    tun.close();
                } catch (IOException ignored) {
                }
            }
            thread = null;
            if (!stopRequested) {
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (Exception ignored) {
        }
    }

    private void handlePacket(byte[] buf, int len) {
        final DnsPacket.Query q = DnsPacket.parse(buf, len);
        if (q == null) return;
        totalCount.incrementAndGet();
        Blocklist list = blocklist;
        if (list != null && list.isBlocked(q.name)) {
            blockedCount.incrementAndGet();
            remember(q.name);
            writeToTun(DnsPacket.wrap(q, DnsPacket.blockedResponse(q)));
            return;
        }
        pool.execute(() -> forward(q));
    }

    private void writeToTun(byte[] packet) {
        try {
            synchronized (tunOut) {
                tunOut.write(packet);
            }
        } catch (IOException ignored) {
            // Tunnel fermé ou saturé : le client DNS réessaiera.
        }
    }

    private void forward(DnsPacket.Query q) {
        List<Upstream> candidates = new ArrayList<>();
        Upstream good = lastGood;
        if (good != null) candidates.add(good);
        for (Upstream u : currentUpstreams()) {
            if (good == null || !u.address.equals(good.address)) candidates.add(u);
        }
        int tries = 0;
        for (Upstream u : candidates) {
            if (stopRequested || tries++ >= 4) return;
            byte[] answer = ask(u, q.dns);
            if (answer != null) {
                lastGood = u;
                if (answer.length + 28 <= MTU) writeToTun(DnsPacket.wrap(q, answer));
                return;
            }
            if (u == good) {
                lastGood = null;
                upstreamsAt = 0; // le réseau a sans doute changé
            }
        }
    }

    private byte[] ask(Upstream u, byte[] query) {
        try (DatagramSocket s = new DatagramSocket()) {
            protect(s);
            if (u.network != null) u.network.bindSocket(s);
            s.setSoTimeout(2500);
            s.send(new DatagramPacket(query, query.length, u.address, 53));
            byte[] buf = new byte[MTU];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            s.receive(in);
            int n = in.getLength();
            if (n < 12 || buf[0] != query[0] || buf[1] != query[1]) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** DNS du réseau réel (hors VPN), puis résolveurs publics en secours. */
    private List<Upstream> currentUpstreams() {
        List<Upstream> cached = upstreams;
        long now = System.nanoTime();
        if (cached != null && upstreamsAt != 0 && now - upstreamsAt < 20_000_000_000L) return cached;

        List<Upstream> list = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps == null
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                for (InetAddress a : lp.getDnsServers()) {
                    if (a.isLinkLocalAddress() || a.isLoopbackAddress() || a.isAnyLocalAddress()) continue;
                    Upstream u = new Upstream(n, a);
                    if (validated) list.add(0, u);
                    else list.add(u);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Lecture des DNS système impossible", e);
        }
        for (String ip : FALLBACK_DNS) {
            try {
                list.add(new Upstream(null, InetAddress.getByName(ip)));
            } catch (Exception ignored) {
            }
        }
        upstreams = list;
        upstreamsAt = now == 0 ? 1 : now;
        return list;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Blocage actif",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AdVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return nb.setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("PubStop est actif")
                .setContentText("Les publicités sont bloquées")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build())
                .build();
    }
}
