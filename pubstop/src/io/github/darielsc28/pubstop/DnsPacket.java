package io.github.darielsc28.pubstop;

import java.util.Locale;

/**
 * Lecture / écriture minimale de paquets IPv4 + UDP + DNS.
 * Aucune dépendance Android : testable sur une JVM classique.
 */
final class DnsPacket {
    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;

    /** Requête DNS extraite d'un paquet IP sortant. */
    static final class Query {
        final byte[] srcAddr = new byte[4];
        final byte[] dstAddr = new byte[4];
        int srcPort;
        int dstPort;
        /** Charge utile DNS brute. */
        byte[] dns;
        /** Nom demandé, en minuscules, sans point final. */
        String name;
        int qtype;
        /** Position de la fin de la section question dans {@link #dns}. */
        int questionEnd;
    }

    private DnsPacket() {}

    /** @return la requête, ou null si le paquet n'est pas une requête DNS IPv4/UDP exploitable. */
    static Query parse(byte[] p, int len) {
        if (len < 28 || (p[0] & 0xF0) != 0x40) return null;
        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8 || (p[9] & 0xFF) != 17) return null;
        // Fragment (hors premier) : on ignore.
        if ((((p[6] & 0x1F) << 8) | (p[7] & 0xFF)) != 0) return null;

        Query q = new Query();
        System.arraycopy(p, 12, q.srcAddr, 0, 4);
        System.arraycopy(p, 16, q.dstAddr, 0, 4);
        q.srcPort = u16(p, ihl);
        q.dstPort = u16(p, ihl + 2);
        if (q.dstPort != 53) return null;
        int udpLen = u16(p, ihl + 4);
        if (udpLen < 8 + 12 || ihl + udpLen > len) return null;

        q.dns = new byte[udpLen - 8];
        System.arraycopy(p, ihl + 8, q.dns, 0, q.dns.length);
        return parseQuestion(q) ? q : null;
    }

    private static boolean parseQuestion(Query q) {
        byte[] d = q.dns;
        if ((d[2] & 0x80) != 0) return false;      // c'est une réponse
        if (u16(d, 4) != 1) return false;          // une seule question attendue
        StringBuilder sb = new StringBuilder();
        int pos = 12;
        while (true) {
            if (pos >= d.length) return false;
            int l = d[pos] & 0xFF;
            if (l == 0) { pos++; break; }
            if ((l & 0xC0) != 0) return false;     // pas de compression dans une question
            if (pos + 1 + l > d.length) return false;
            if (sb.length() > 0) sb.append('.');
            for (int i = 0; i < l; i++) sb.append((char) (d[pos + 1 + i] & 0xFF));
            pos += 1 + l;
        }
        if (pos + 4 > d.length) return false;
        q.name = sb.toString().toLowerCase(Locale.ROOT);
        q.qtype = u16(d, pos);
        q.questionEnd = pos + 4;
        return true;
    }

    /**
     * Réponse « bloquée » : 0.0.0.0 pour A, :: pour AAAA, réponse vide sinon.
     * Le navigateur échoue immédiatement au lieu d'attendre un délai.
     */
    static byte[] blockedResponse(Query q) {
        int rdLen = q.qtype == TYPE_A ? 4 : q.qtype == TYPE_AAAA ? 16 : 0;
        int answerLen = rdLen == 0 ? 0 : 12 + rdLen;
        byte[] r = new byte[q.questionEnd + answerLen];
        System.arraycopy(q.dns, 0, r, 0, q.questionEnd);
        r[2] = (byte) (0x80 | (q.dns[2] & 0x79));  // QR=1, opcode et RD recopiés
        r[3] = (byte) 0x80;                        // RA=1, RCODE=0
        put16(r, 4, 1);
        put16(r, 6, rdLen == 0 ? 0 : 1);
        put16(r, 8, 0);
        put16(r, 10, 0);
        if (rdLen != 0) {
            int o = q.questionEnd;
            r[o] = (byte) 0xC0; r[o + 1] = 0x0C;   // pointeur vers le nom de la question
            put16(r, o + 2, q.qtype);
            put16(r, o + 4, 1);                    // classe IN
            put16(r, o + 6, 0); put16(r, o + 8, 300); // TTL 5 min
            put16(r, o + 10, rdLen);               // RDATA reste à zéro
        }
        return r;
    }

    /** Emballe une réponse DNS dans un paquet IPv4/UDP renvoyé à l'émetteur de la requête. */
    static byte[] wrap(Query q, byte[] dns) {
        int total = 28 + dns.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(q.dstAddr, 0, p, 12, 4);
        System.arraycopy(q.srcAddr, 0, p, 16, 4);
        put16(p, 10, ipChecksum(p, 20));
        put16(p, 20, q.dstPort);
        put16(p, 22, q.srcPort);
        put16(p, 24, 8 + dns.length);
        // Somme de contrôle UDP à 0 : facultative en IPv4.
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    static int ipChecksum(byte[] p, int headerLen) {
        int sum = 0;
        for (int i = 0; i < headerLen; i += 2) sum += u16(p, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }
}
