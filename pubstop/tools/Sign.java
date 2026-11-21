import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signe un APK (schéma v2, suffisant à partir d'Android 7) puis vérifie la signature. Usage : Sign keystore motdepasse alias entrée sortie */
public class Sign {
    public static void main(String[] a) throws Exception {
        char[] pass = a[1].toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[0])) {
            ks.load(in, pass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(a[2], pass);
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[2]);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "PUBSTOP", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(a[3]))
                .setOutputApk(new File(a[4]))
                .setMinSdkVersion(24)
                .setV1SigningEnabled(false) // la signature v1 de cette version d'apksig ne fonctionne plus sur les JDK récents
                .setV2SigningEnabled(true)
                .build()
                .sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[4])).build().verify();
        System.out.println("Signature : v1=" + r.isVerifiedUsingV1Scheme() + " v2=" + r.isVerifiedUsingV2Scheme());
        if (!r.isVerified() || !r.isVerifiedUsingV2Scheme()) {
            System.err.println("Erreurs : " + r.getErrors());
            System.exit(1);
        }
    }
}
