package io.github.darielsc28.pubstop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Tests JVM de l'analyse DNS et de la liste de blocage (sans Android). */
public class DnsTest {
    static int failures;

    static void check(boolean cond, String what) {
        if (!cond) {
            failures++;
            System.out.println("ÉCHEC : " + what);
        }
    }

    static byte[] query(String name, int qtype, boolean edns) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(0xAB); d.write(0xCD);            // id
        d.write(0x01); d.write(0x00);            // RD
        d.write(0); d.write(1);                  // QDCOUNT
        d.write(0); d.write(0); d.write(0); d.write(0);
        d.write(0); d.write(edns ? 1 : 0);       // ARCOUNT
        for (String label : name.split("\\.")) {
            d.write(label.length());
            d.write(label.getBytes(StandardCharsets.US_ASCII), 0, label.length());
        }
        d.write(0);
        d.write(qtype >> 8); d.write(qtype);
        d.write(0); d.write(1);
        if (edns) {
            byte[] opt = {0, 0, 41, 0x04, (byte) 0xD0, 0, 0, 0, 0, 0, 0};
            d.write(opt, 0, opt.length);
        }
        byte[] dns = d.toByteArray();
        byte[] p = new byte[28 + dns.length];
        p[0] = 0x45;
        p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length;
        p[8] = 64; p[9] = 17;
        p[12] = 10; p[13] = 111; p[14] = (byte) 222; p[15] = 1;
        p[16] = 10; p[17] = 111; p[18] = (byte) 222; p[19] = 2;
        p[20] = (byte) 0xC3; p[21] = 0x50;       // port source 50000
        p[23] = 53;
        p[24] = (byte) ((8 + dns.length) >> 8); p[25] = (byte) (8 + dns.length);
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    public static void main(String[] args) throws Exception {
        Blocklist list = Blocklist.load(new ByteArrayInputStream((
                "# commentaire\n127.0.0.1 localhost\n0.0.0.0 0.0.0.0\n"
                + "0.0.0.0 Ads.Example.com # pub\ntracker.example.net\n\n::1 ip6-localhost\n")
                .getBytes(StandardCharsets.UTF_8)));
        check(list.size() == 2, "taille de liste = " + list.size());
        check(list.isBlocked("ads.example.com"), "ads.example.com bloqué");
        check(list.isBlocked("tracker.example.net"), "format domaine seul");
        check(!list.isBlocked("example.com"), "example.com non bloqué");
        check(!list.isBlocked("localhost"), "localhost ignoré");

        for (boolean edns : new boolean[] {false, true}) {
            byte[] p = query("ADS.example.com", DnsPacket.TYPE_A, edns);
            DnsPacket.Query q = DnsPacket.parse(p, p.length);
            check(q != null, "requête analysée");
            check("ads.example.com".equals(q.name), "nom en minuscules : " + q.name);
            check(q.qtype == DnsPacket.TYPE_A && q.srcPort == 50000 && q.dstPort == 53, "type et ports");

            byte[] r = DnsPacket.blockedResponse(q);
            check(r[0] == (byte) 0xAB && r[1] == (byte) 0xCD, "id recopié");
            check((r[2] & 0x80) != 0 && (r[3] & 0x0F) == 0, "QR=1, RCODE=0");
            check(DnsPacket.u16(r, 6) == 1 && DnsPacket.u16(r, 10) == 0, "1 réponse, pas d'additionnel");
            check(r.length == q.questionEnd + 16, "longueur réponse A");
            check(r[r.length - 1] == 0 && r[r.length - 4] == 0, "adresse 0.0.0.0");

            byte[] w = DnsPacket.wrap(q, r);
            check(w.length == 28 + r.length && DnsPacket.u16(w, 2) == w.length, "longueur IP");
            check(w[12] == 10 && w[15] == 2 && w[19] == 1, "adresses inversées");
            check(DnsPacket.u16(w, 20) == 53 && DnsPacket.u16(w, 22) == 50000, "ports inversés");
            check(DnsPacket.ipChecksum(w, 20) == 0, "somme de contrôle IP valide");
            check(DnsPacket.u16(w, 24) == 8 + r.length, "longueur UDP");
        }

        byte[] p6 = query("ads.example.com", DnsPacket.TYPE_AAAA, false);
        DnsPacket.Query q6 = DnsPacket.parse(p6, p6.length);
        check(DnsPacket.blockedResponse(q6).length == q6.questionEnd + 28, "longueur réponse AAAA");
        byte[] p65 = query("ads.example.com", 65, false);
        DnsPacket.Query q65 = DnsPacket.parse(p65, p65.length);
        byte[] r65 = DnsPacket.blockedResponse(q65);
        check(r65.length == q65.questionEnd && DnsPacket.u16(r65, 6) == 0, "réponse vide pour HTTPS");

        // Paquets à ignorer sans planter.
        byte[] tcp = query("a.example.com", 1, false); tcp[9] = 6;
        check(DnsPacket.parse(tcp, tcp.length) == null, "TCP ignoré");
        byte[] other = query("a.example.com", 1, false); other[23] = 80;
        check(DnsPacket.parse(other, other.length) == null, "autre port ignoré");
        byte[] v6 = query("a.example.com", 1, false); v6[0] = 0x60;
        check(DnsPacket.parse(v6, v6.length) == null, "IPv6 ignoré");
        byte[] full = query("a.example.com", 1, false);
        for (int n = 0; n < full.length; n++) DnsPacket.parse(full, n);       // tronqué
        byte[] junk = query("a.example.com", 1, false); junk[40] = (byte) 0xC0;
        DnsPacket.parse(junk, junk.length);
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 200000; i++) {
            byte[] f = query("a.example.com", 1, (i & 1) == 0);
            for (int k = 0; k < 3; k++) f[rnd.nextInt(f.length)] = (byte) rnd.nextInt(256);
            DnsPacket.Query q = DnsPacket.parse(f, f.length);
            if (q != null) DnsPacket.wrap(q, DnsPacket.blockedResponse(q));
        }

        System.out.println(failures == 0 ? "Tests OK" : failures + " échec(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
