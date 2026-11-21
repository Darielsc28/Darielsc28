package io.github.darielsc28.pubstop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends Activity {
    private static final String LIST_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final int REQ_VPN = 1;
    private static final int REQ_NOTIF = 2;

    private static final int BG = 0xFF0F1419;
    private static final int CARD = 0xFF1A222B;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int MUTED = 0xFF8D9AA8;
    private static final int GREEN = 0xFF2EBD73;
    private static final int GREY = 0xFF3A4653;
    private static final int AMBER = 0xFFE3A53B;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private TextView status;
    private Button toggle;
    private TextView counters;
    private TextView listInfo;
    private Button update;
    private TextView warning;
    private TextView recent;
    private boolean updating;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));

        TextView title = text("PubStop", 28, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Bloqueur de publicités pour le navigateur", 14, MUTED));

        status = text("", 20, TEXT);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        root.addView(status, margins(0, 32, 0, 12));

        toggle = button("");
        toggle.setTextSize(18);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        counters = text("", 15, TEXT);
        root.addView(card(counters), margins(0, 20, 0, 0));

        warning = text("Le « DNS privé » d'Android est réglé sur un fournisseur précis : il contourne "
                + "le blocage. Réglez-le sur « Automatique » ou « Désactivé » dans "
                + "Paramètres › Réseau › DNS privé.", 14, AMBER);
        View warningCard = card(warning);
        warningCard.setTag("warning");
        root.addView(warningCard, margins(0, 12, 0, 0));

        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listInfo = text("", 14, MUTED);
        listBox.addView(listInfo);
        update = button("Mettre à jour la liste");
        update.setOnClickListener(v -> onUpdate());
        listBox.addView(update, margins(0, 10, 0, 0));
        root.addView(card(listBox), margins(0, 12, 0, 0));

        LinearLayout recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        TextView recentTitle = text("Derniers domaines bloqués", 14, MUTED);
        recentBox.addView(recentTitle);
        recent = text("", 13, TEXT);
        recent.setTypeface(Typeface.MONOSPACE);
        recent.setTextIsSelectable(true);
        recentBox.addView(recent, margins(0, 8, 0, 0));
        root.addView(card(recentBox), margins(0, 12, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void refresh() {
        boolean on = AdVpnService.running;
        status.setText(on ? "Protection active" : "Protection désactivée");
        status.setTextColor(on ? GREEN : MUTED);
        toggle.setText(on ? "Désactiver" : "Activer le blocage");
        toggle.setBackground(rounded(on ? GREY : GREEN, 14));

        long blocked = AdVpnService.blockedCount.get();
        long total = AdVpnService.totalCount.get();
        String pct = total == 0 ? "" : "  (" + (blocked * 100 / total) + " %)";
        counters.setText("Requêtes bloquées : " + blocked + pct + "\nRequêtes analysées : " + total);

        File f = new File(getFilesDir(), AdVpnService.HOSTS_FILE);
        String origin = f.exists()
                ? "mise à jour le " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(f.lastModified()))
                : "version fournie avec l'application";
        int size = AdVpnService.listSize;
        if (!updating) {
            listInfo.setText("Liste de blocage : " + (size > 0 ? size + " domaines, " : "") + origin);
        }

        ((View) warning.getParent()).setVisibility(strictPrivateDns() ? View.VISIBLE : View.GONE);

        List<String> names = AdVpnService.recentBlocked();
        if (names.isEmpty()) {
            recent.setText(on ? "Aucun pour l'instant. Ouvrez une page dans votre navigateur."
                    : "Activez le blocage pour commencer.");
            recent.setTextColor(MUTED);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String n : names) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(n);
            }
            if (!sb.toString().contentEquals(recent.getText())) recent.setText(sb);
            recent.setTextColor(TEXT);
        }
    }

    /** Vrai si le DNS privé (DNS-over-TLS) est forcé vers un fournisseur : il ignore alors le DNS du VPN. */
    private boolean strictPrivateDns() {
        try {
            return "hostname".equals(Settings.Global.getString(getContentResolver(), "private_dns_mode"));
        } catch (Exception e) {
            return false;
        }
    }

    private void onToggle() {
        if (AdVpnService.running) {
            startService(new Intent(this, AdVpnService.class).setAction(AdVpnService.ACTION_STOP));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        prepareVpn();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // La notification est facultative : on démarre que l'autorisation soit accordée ou non.
        if (requestCode == REQ_NOTIF) prepareVpn();
    }

    private void prepareVpn() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN);
        else startVpn();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) startVpn();
        else Toast.makeText(this, "Autorisation VPN refusée : le blocage ne peut pas démarrer.",
                Toast.LENGTH_LONG).show();
    }

    private void startVpn() {
        Intent i = new Intent(this, AdVpnService.class).setAction(AdVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        handler.postDelayed(this::refresh, 500);
    }

    private void onUpdate() {
        if (updating) return;
        updating = true;
        update.setEnabled(false);
        listInfo.setText("Téléchargement de la liste…");
        new Thread(() -> {
            final String error = download();
            handler.post(() -> {
                updating = false;
                update.setEnabled(true);
                if (error == null) {
                    if (AdVpnService.running) {
                        startService(new Intent(this, AdVpnService.class).setAction(AdVpnService.ACTION_RELOAD));
                    } else {
                        AdVpnService.listSize = 0;
                    }
                    Toast.makeText(this, "Liste mise à jour.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Échec de la mise à jour : " + error, Toast.LENGTH_LONG).show();
                }
                refresh();
            });
        }, "pubstop-update").start();
    }

    /** @return null en cas de succès, sinon un message d'erreur. */
    private String download() {
        File tmp = new File(getFilesDir(), AdVpnService.HOSTS_FILE + ".tmp");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(LIST_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return "réponse " + c.getResponseCode();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 15];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 30_000_000) return "fichier trop volumineux";
                    out.write(buf, 0, n);
                }
            }
            int count;
            try (InputStream in = new FileInputStream(tmp)) {
                count = Blocklist.load(in).size();
            }
            if (count < 1000) return "liste invalide";
            if (!tmp.renameTo(new File(getFilesDir(), AdVpnService.HOSTS_FILE))) return "écriture impossible";
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
            tmp.delete();
        }
    }

    // --- Petits utilitaires d'interface ---

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(GREY, 14));
        b.setStateListAnimator(null);
        return b;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private View card(View content) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(CARD, 14));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.addView(content);
        return box;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
}
