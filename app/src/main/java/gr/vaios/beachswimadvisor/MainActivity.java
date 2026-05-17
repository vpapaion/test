package gr.vaios.beachswimadvisor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Beach> allBeaches = new ArrayList<>();
    private final List<Beach> shownBeaches = new ArrayList<>();

    private EditText searchBox;
    private Spinner beachSpinner;
    private ProgressBar progressBar;
    private TextView resultTitle;
    private TextView resultDetails;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadBeaches();
        refreshSpinner("");
    }

    private View buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(18));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Παραλίες για Μπάνιο");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(0, 96, 88));
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Επιλέξτε παραλία και η εφαρμογή υπολογίζει αν οι σημερινές συνθήκες είναι καλές για μπάνιο.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, dp(6), 0, dp(16));
        root.addView(subtitle);

        searchBox = new EditText(this);
        searchBox.setHint("Αναζήτηση παραλίας ή περιοχής");
        searchBox.setSingleLine(true);
        root.addView(searchBox);

        beachSpinner = new Spinner(this);
        root.addView(beachSpinner);

        Button checkButton = new Button(this);
        checkButton.setText("Έλεγχος καταλληλότητας");
        checkButton.setAllCaps(false);
        root.addView(checkButton);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        p.setMargins(0, dp(12), 0, dp(12));
        root.addView(progressBar, p);

        resultTitle = new TextView(this);
        resultTitle.setText("Δεν έχει γίνει έλεγχος ακόμη.");
        resultTitle.setTextSize(22);
        resultTitle.setTextColor(Color.DKGRAY);
        resultTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(resultTitle);

        resultDetails = new TextView(this);
        resultDetails.setTextSize(16);
        resultDetails.setTextColor(Color.rgb(45, 45, 45));
        resultDetails.setLineSpacing(0, 1.15f);
        root.addView(resultDetails);

        TextView source = new TextView(this);
        source.setText("Πηγές: Open-Meteo Weather API και Marine API. Δεν αντικαθιστά επίσημες οδηγίες ασφάλειας ή ποιότητας υδάτων.");
        source.setTextSize(12);
        source.setTextColor(Color.GRAY);
        source.setPadding(0, dp(18), 0, 0);
        root.addView(source);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshSpinner(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        checkButton.setOnClickListener(v -> checkSelectedBeach());
        return scrollView;
    }

    private void loadBeaches() {
        try {
            JSONArray array = new JSONArray(readAsset("beaches.json"));
            allBeaches.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                allBeaches.add(new Beach(o.getString("name"), o.getString("area"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("facingDeg"), o.optString("note", "")));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Δεν φορτώθηκε η βάση παραλιών: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshSpinner(String filter) {
        String q = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        shownBeaches.clear();
        List<String> labels = new ArrayList<>();
        for (Beach b : allBeaches) {
            String hay = (b.name + " " + b.area).toLowerCase(Locale.ROOT);
            if (q.isEmpty() || hay.contains(q)) {
                shownBeaches.add(b);
                labels.add(b.name + " — " + b.area);
            }
        }
        if (labels.isEmpty()) labels.add("Δεν βρέθηκε παραλία");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        beachSpinner.setAdapter(adapter);
    }

    private void checkSelectedBeach() {
        int pos = beachSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= shownBeaches.size()) {
            Toast.makeText(this, "Επιλέξτε παραλία.", Toast.LENGTH_SHORT).show();
            return;
        }
        Beach beach = shownBeaches.get(pos);
        progressBar.setVisibility(View.VISIBLE);
        resultTitle.setText("Λήψη δεδομένων για: " + beach.name);
        resultDetails.setText("Παρακαλώ περιμένετε λίγα δευτερόλεπτα.");

        executor.execute(() -> {
            try {
                Conditions c = fetchConditions(beach);
                Suitability s = calculateSuitability(beach, c);
                mainHandler.post(() -> showResult(beach, c, s));
            } catch (Exception e) {
                mainHandler.post(() -> showError(e));
            }
        });
    }

    private Conditions fetchConditions(Beach beach) throws Exception {
        String lat = String.format(Locale.US, "%.5f", beach.lat);
        String lon = String.format(Locale.US, "%.5f", beach.lon);
        String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,apparent_temperature,precipitation,wind_speed_10m,wind_direction_10m&hourly=precipitation_probability&forecast_days=1&timezone=auto";
        String marineUrl = "https://marine-api.open-meteo.com/v1/marine?latitude=" + lat + "&longitude=" + lon + "&current=wave_height,wave_direction,sea_surface_temperature&hourly=wave_height,wave_direction,sea_surface_temperature&forecast_days=1&timezone=auto";
        JSONObject weather = new JSONObject(httpGet(weatherUrl));
        JSONObject marine = new JSONObject(httpGet(marineUrl));
        JSONObject cw = weather.optJSONObject("current");
        if (cw == null) throw new Exception("Δεν επιστράφηκαν τρέχοντα δεδομένα καιρού.");
        String time = cw.optString("time", "");
        Conditions c = new Conditions();
        c.time = time;
        c.airTemp = cw.optDouble("temperature_2m", Double.NaN);
        c.apparentTemp = cw.optDouble("apparent_temperature", Double.NaN);
        c.precipitation = cw.optDouble("precipitation", Double.NaN);
        c.windSpeed = cw.optDouble("wind_speed_10m", Double.NaN);
        c.windDirection = cw.optDouble("wind_direction_10m", Double.NaN);
        c.rainProbability = extractHourlyValue(weather, "precipitation_probability", time);
        JSONObject cm = marine.optJSONObject("current");
        if (cm != null) {
            c.waveHeight = cm.optDouble("wave_height", Double.NaN);
            c.waveDirection = cm.optDouble("wave_direction", Double.NaN);
            c.seaTemp = cm.optDouble("sea_surface_temperature", Double.NaN);
        }
        if (Double.isNaN(c.waveHeight)) c.waveHeight = extractHourlyValue(marine, "wave_height", time);
        if (Double.isNaN(c.waveDirection)) c.waveDirection = extractHourlyValue(marine, "wave_direction", time);
        if (Double.isNaN(c.seaTemp)) c.seaTemp = extractHourlyValue(marine, "sea_surface_temperature", time);
        return c;
    }

    private double extractHourlyValue(JSONObject root, String variable, String targetTime) {
        try {
            JSONObject hourly = root.optJSONObject("hourly");
            if (hourly == null) return Double.NaN;
            JSONArray times = hourly.optJSONArray("time");
            JSONArray values = hourly.optJSONArray(variable);
            if (times == null || values == null || values.length() == 0) return Double.NaN;
            String targetHour = targetTime != null && targetTime.length() >= 13 ? targetTime.substring(0, 13) : "";
            int best = 0;
            for (int i = 0; i < times.length(); i++) {
                if (!targetHour.isEmpty() && times.optString(i, "").startsWith(targetHour)) { best = i; break; }
            }
            return values.optDouble(best, Double.NaN);
        } catch (Exception ignored) { return Double.NaN; }
    }

    private Suitability calculateSuitability(Beach beach, Conditions c) {
        double score = 0;
        StringBuilder notes = new StringBuilder();
        score += idealRangeScore(c.airTemp, 24, 32, 18, 36, 20);
        score += idealRangeScore(c.seaTemp, 22, 28, 18, 30, 20);
        score += windScore(c.windSpeed, 20);
        score += rainScore(c.rainProbability, 15);
        score += waveScore(c.waveHeight, 15);
        score += windDirectionScore(beach, c, notes, 10);
        score = Math.max(0, Math.min(100, score));
        Suitability s = new Suitability();
        s.score = (int) Math.round(score);
        if (score >= 80) { s.label = "Πολύ κατάλληλη"; s.color = Color.rgb(0, 128, 96); s.summary = "Οι συνθήκες φαίνονται πολύ καλές για μπάνιο."; }
        else if (score >= 65) { s.label = "Κατάλληλη"; s.color = Color.rgb(60, 150, 60); s.summary = "Οι συνθήκες είναι γενικά καλές, με μικρές επιφυλάξεις."; }
        else if (score >= 45) { s.label = "Μέτρια"; s.color = Color.rgb(210, 140, 0); s.summary = "Μπορεί να γίνει μπάνιο, αλλά υπάρχουν παράγοντες που θέλουν προσοχή."; }
        else { s.label = "Όχι καλή επιλογή"; s.color = Color.rgb(190, 50, 50); s.summary = "Οι τρέχουσες συνθήκες δεν φαίνονται ιδανικές για μπάνιο."; }
        s.notes = notes.length() == 0 ? "Δεν εντοπίστηκε ιδιαίτερο πρόβλημα από την κατεύθυνση του ανέμου." : notes.toString();
        return s;
    }

    private double idealRangeScore(double value, double goodMin, double goodMax, double okMin, double okMax, double weight) {
        if (Double.isNaN(value)) return weight * 0.55;
        if (value >= goodMin && value <= goodMax) return weight;
        if (value < okMin || value > okMax) return weight * 0.15;
        if (value < goodMin) return weight * (0.35 + 0.65 * ((value - okMin) / (goodMin - okMin)));
        return weight * (0.35 + 0.65 * ((okMax - value) / (okMax - goodMax)));
    }
    private double windScore(double kmh, double weight) { if (Double.isNaN(kmh)) return weight * 0.55; if (kmh <= 12) return weight; if (kmh <= 22) return weight * 0.75; if (kmh <= 32) return weight * 0.45; if (kmh <= 42) return weight * 0.20; return 0; }
    private double rainScore(double p, double weight) { if (Double.isNaN(p)) return weight * 0.55; if (p <= 10) return weight; if (p <= 30) return weight * 0.75; if (p <= 55) return weight * 0.40; if (p <= 75) return weight * 0.20; return 0; }
    private double waveScore(double m, double weight) { if (Double.isNaN(m)) return weight * 0.55; if (m <= 0.35) return weight; if (m <= 0.75) return weight * 0.75; if (m <= 1.15) return weight * 0.45; if (m <= 1.60) return weight * 0.20; return 0; }

    private double windDirectionScore(Beach beach, Conditions c, StringBuilder notes, double weight) {
        if (Double.isNaN(c.windDirection) || Double.isNaN(c.windSpeed)) return weight * 0.55;
        if (c.windSpeed <= 12) return weight;
        double onshore = angleDiff(c.windDirection, beach.facingDeg);
        double offshore = angleDiff(c.windDirection, normalizeDeg(beach.facingDeg + 180));
        if (onshore <= 45) { notes.append("Ο άνεμος φαίνεται να έρχεται από τη θάλασσα προς την ακτή· μπορεί να σηκώνει κύμα."); return c.windSpeed > 25 ? weight * 0.15 : weight * 0.35; }
        if (offshore <= 45) { notes.append("Ο άνεμος φαίνεται να έρχεται από τη στεριά προς τη θάλασσα· προσοχή με στρώματα/φουσκωτά."); return c.windSpeed > 25 ? weight * 0.30 : weight * 0.55; }
        return weight * 0.85;
    }

    private void showResult(Beach beach, Conditions c, Suitability s) {
        progressBar.setVisibility(View.GONE);
        resultTitle.setText(s.label + " — " + s.score + "/100");
        resultTitle.setTextColor(s.color);
        StringBuilder out = new StringBuilder();
        out.append(s.summary).append("\n\n");
        out.append("Παραλία: ").append(beach.name).append(" — ").append(beach.area).append("\n");
        out.append("Συντεταγμένες: ").append(fmt(beach.lat, 4)).append(", ").append(fmt(beach.lon, 4)).append("\n");
        out.append("Προσανατολισμός ακτής: ").append(directionLabel(beach.facingDeg)).append(" (").append(fmt(beach.facingDeg, 0)).append("°)\n");
        if (!beach.note.isEmpty()) out.append("Σημείωση: ").append(beach.note).append("\n");
        out.append("\nΤρέχοντα δεδομένα\n");
        out.append("Ώρα δεδομένων: ").append(c.time == null || c.time.isEmpty() ? "—" : c.time).append("\n");
        out.append("Θερμοκρασία αέρα: ").append(fmtUnit(c.airTemp, "°C")).append("\n");
        out.append("Αισθητή θερμοκρασία: ").append(fmtUnit(c.apparentTemp, "°C")).append("\n");
        out.append("Θερμοκρασία θάλασσας: ").append(fmtUnit(c.seaTemp, "°C")).append("\n");
        out.append("Άνεμος: ").append(fmtUnit(c.windSpeed, "km/h"));
        if (!Double.isNaN(c.windDirection)) out.append(" από ").append(directionLabel(c.windDirection)).append(" (").append(fmt(c.windDirection, 0)).append("°)");
        out.append("\nΠιθανότητα βροχόπτωσης: ").append(fmtUnit(c.rainProbability, "%")).append("\n");
        out.append("Τρέχουσα βροχόπτωση: ").append(fmtUnit(c.precipitation, "mm")).append("\n");
        out.append("Ύψος κύματος: ").append(fmtUnit(c.waveHeight, "m"));
        if (!Double.isNaN(c.waveDirection)) out.append(" από ").append(directionLabel(c.waveDirection)).append(" (").append(fmt(c.waveDirection, 0)).append("°)");
        out.append("\n\nΕρμηνεία ανέμου\n").append(s.notes).append("\n\n");
        out.append("Σημαντικό: η εφαρμογή αξιολογεί καιρικές/θαλάσσιες συνθήκες. Δεν ελέγχει σε πραγματικό χρόνο μικροβιολογική ποιότητα νερού.");
        resultDetails.setText(out.toString());
    }

    private void showError(Exception e) {
        progressBar.setVisibility(View.GONE);
        resultTitle.setText("Αποτυχία λήψης δεδομένων");
        resultTitle.setTextColor(Color.rgb(190, 50, 50));
        resultDetails.setText("Ελέγξτε τη σύνδεση στο Internet και δοκιμάστε ξανά.\n\nΤεχνική λεπτομέρεια: " + e.getMessage());
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + body);
            return body;
        } finally { if (conn != null) conn.disconnect(); }
    }
    private String readAsset(String name) throws Exception { try (InputStream is = getAssets().open(name)) { return readStream(is); } }
    private String readStream(InputStream is) throws Exception { StringBuilder sb = new StringBuilder(); try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { String line; while ((line = br.readLine()) != null) sb.append(line).append('\n'); } return sb.toString(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private String fmtUnit(double v, String unit) { if (Double.isNaN(v)) return "μη διαθέσιμο"; return fmt(v, unit.equals("°C") || unit.equals("m") || unit.equals("mm") ? 1 : 0) + " " + unit; }
    private String fmt(double v, int d) { if (Double.isNaN(v)) return "—"; return String.format(Locale.US, "% ." + d + "f", v).trim(); }
    private double angleDiff(double a, double b) { double d = Math.abs(normalizeDeg(a) - normalizeDeg(b)); return d > 180 ? 360 - d : d; }
    private double normalizeDeg(double deg) { double x = deg % 360; return x < 0 ? x + 360 : x; }
    private String directionLabel(double deg) { if (Double.isNaN(deg)) return "—"; String[] labels = {"Β", "ΒΑ", "Α", "ΝΑ", "Ν", "ΝΔ", "Δ", "ΒΔ"}; return labels[(int) Math.round(normalizeDeg(deg) / 45.0) % 8]; }

    private static class Beach { final String name, area, note; final double lat, lon, facingDeg; Beach(String name, String area, double lat, double lon, double facingDeg, String note) { this.name = name; this.area = area; this.lat = lat; this.lon = lon; this.facingDeg = facingDeg; this.note = note; } }
    private static class Conditions { String time; double airTemp, apparentTemp, seaTemp, windSpeed, windDirection, rainProbability, precipitation, waveHeight, waveDirection; }
    private static class Suitability { int score; String label, summary, notes; int color; }
}
