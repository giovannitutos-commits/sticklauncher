package br.com.stocco.auroratv;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(2);
    final android.util.LruCache<String, Bitmap> images = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    android.content.SharedPreferences prefs;
    LinearLayout body, dock, nextRow, appsRow;
    TextView weather, clock, nextHint;
    ScrollView scroll;
    final ArrayList<App> apps = new ArrayList<>();
    boolean resumed, weatherLoading;
    int generation;
    static final int WHITE = Color.rgb(244,247,252), MUTED = Color.rgb(177,194,210);
    static class App {
        String name, pkg;
        Intent intent;
        Drawable icon;
    }
    static class Program {
        String title, description, art, intent, pkg;
        int position, duration;
    }
    final Runnable weatherTick = new Runnable() {
        public void run() { refreshWeather(); if (resumed) ui.postDelayed(this, 30 * 60 * 1000L); }
    };
    final ContentObserver programsObserver = new ContentObserver(ui) {
        public void onChange(boolean self) { if (resumed) { ui.removeCallbacks(reloadPrograms); ui.postDelayed(reloadPrograms, 500); } }
    };
    final Runnable reloadPrograms = () -> loadPrograms();
    int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("aurora", MODE_PRIVATE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        FrameLayout root = new FrameLayout(this);
        root.addView(new AuroraBackground(this), new FrameLayout.LayoutParams(-1,-1));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setPadding(dp(42),dp(24),dp(42),dp(28));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body); root.addView(scroll); setContentView(root);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        weather = text("Escolher cidade  ›", 17, WHITE); weather.setPadding(dp(16),dp(10),dp(16),dp(10));
        focusable(weather, () -> chooseCity());
        top.addView(weather, new LinearLayout.LayoutParams(0,dp(66),1));
        clock = text("", 23, WHITE); clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(clock, new LinearLayout.LayoutParams(dp(160),dp(66)));
        body.addView(top);
        TextView welcome = text("Seu próximo momento.", 32, WHITE);
        welcome.setPadding(0,dp(34),0,0); body.addView(welcome);
        TextView subtitle = text("Tudo o que você gosta, no seu lugar.", 14, MUTED);
        subtitle.setPadding(0,dp(4),0,dp(24)); body.addView(subtitle);
        section("Favoritos", "Segure OK para organizar");
        dock = row(); body.addView(scroller(dock));
        section("Continuar assistindo", "");
        nextRow = row(); body.addView(scroller(nextRow));
        nextHint = text("Buscando conteúdos compartilhados pelos aplicativos…", 13, MUTED);
        nextHint.setPadding(0,dp(8),0,0); body.addView(nextHint);
        section("Seus aplicativos", "");
        appsRow = row(); body.addView(scroller(appsRow));
        LinearLayout controls = row(); controls.setPadding(0,dp(18),0,dp(8));
        controls.addView(button("Clima e cidade", this::chooseCity));
        controls.addView(button("Configurações da TV", () -> open(new Intent(Settings.ACTION_SETTINGS))));
        controls.addView(button("Ajuda", this::help));
        body.addView(controls);
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; immersive(); loadApps(); loadPrograms();
        ui.removeCallbacks(weatherTick); ui.post(weatherTick); ui.post(clockTick);
        try { getContentResolver().registerContentObserver(Uri.parse("content://android.media.tv/watch_next_program"), true, programsObserver); }
        catch (Exception ignored) {}
    }
    final Runnable clockTick = new Runnable() {
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            if (resumed) ui.postDelayed(this, 1000 * 30);
        }
    };
    @Override protected void onPause() {
        resumed = false; ui.removeCallbacks(weatherTick); ui.removeCallbacks(clockTick);
        ui.removeCallbacks(reloadPrograms);
        getContentResolver().unregisterContentObserver(programsObserver);
        super.onPause();
    }
    @Override protected void onDestroy() {
        generation++; io.shutdownNow(); ui.removeCallbacksAndMessages(null); super.onDestroy();
    }
    void immersive() { getWindow().getDecorView().setSystemUiVisibility(5894); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }
    @Override public void onBackPressed() { scroll.smoothScrollTo(0,0); weather.requestFocus(); }
    TextView text(String value, float size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern"); return v;
    }
    GradientDrawable panel(boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            focused ? new int[]{0xFF486278,0xFF263C50} : new int[]{0xD92B3C50,0xCF1B2A3C});
        d.setCornerRadius(dp(17)); d.setStroke(dp(focused ? 2 : 1), focused ? 0xFFADF8E9 : 0x30FFFFFF); return d;
    }
    void focusable(View v, Runnable click) {
        v.setFocusable(true); v.setClickable(true); v.setBackground(panel(false));
        v.setOnClickListener(w -> click.run());
        v.setOnFocusChangeListener((w, f) -> {
            w.setBackground(panel(f)); w.setElevation(dp(f ? 12 : 2));
            w.animate().scaleX(f ? 1.035f : 1f).scaleY(f ? 1.035f : 1f).setDuration(140).start();
        });
    }
    TextView button(String title, Runnable click) {
        TextView t = text(title,14,WHITE); t.setGravity(Gravity.CENTER); t.setPadding(dp(18),dp(12),dp(18),dp(12));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,dp(46)); p.setMargins(0,0,dp(12),0); t.setLayoutParams(p);
        focusable(t,click); return t;
    }
    LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(5),dp(8),dp(10),dp(12)); r.setClipChildren(false); r.setClipToPadding(false); return r;
    }
    HorizontalScrollView scroller(LinearLayout row) {
        HorizontalScrollView s = new HorizontalScrollView(this); s.setHorizontalScrollBarEnabled(false);
        s.setClipToPadding(false); s.setClipChildren(false); s.addView(row);
        return s;
    }
    void section(String title, String hint) {
        LinearLayout line = new LinearLayout(this); line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0,dp(15),0,dp(4));
        line.addView(text(title,19,WHITE),new LinearLayout.LayoutParams(0,-2,1));
        line.addView(text(hint,11,MUTED)); body.addView(line);
    }
    void loadApps() {
        PackageManager pm = getPackageManager(); apps.clear();
        LinkedHashMap<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q,0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !found.containsKey(pkg)) found.put(pkg,ri);
            }
        }
        for (ResolveInfo ri : found.values()) {
            App a = new App(); a.pkg = ri.activityInfo.packageName;
            a.name = ri.loadLabel(pm).toString(); a.icon = ri.loadIcon(pm);
            a.intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(a.pkg,ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            apps.add(a);
        }
        apps.sort((a,b) -> a.name.compareToIgnoreCase(b.name));
        if (!prefs.contains("favorites")) {
            ArrayList<String> initial = new ArrayList<>();
            for (App a : apps) if (a.pkg.toLowerCase(Locale.ROOT).contains("youtube") || a.pkg.toLowerCase(Locale.ROOT).contains("nuvio")) initial.add(a.pkg);
            for (App a : apps) if (initial.size() < 6 && !initial.contains(a.pkg)) initial.add(a.pkg);
            saveFavorites(initial);
        }
        renderApps();
    }
    ArrayList<String> favorites() {
        ArrayList<String> out = new ArrayList<>();
        try { JSONArray j = new JSONArray(prefs.getString("favorites","[]")); for (int i=0;i<j.length();i++) out.add(j.getString(i)); }
        catch (Exception ignored) {} return out;
    }
    void saveFavorites(ArrayList<String> values) { prefs.edit().putString("favorites",new JSONArray(values).toString()).apply(); }
    void renderApps() {
        dock.removeAllViews(); appsRow.removeAllViews();
        for (String pkg : favorites()) for (App a : apps) if (a.pkg.equals(pkg)) dock.addView(appCard(a));
        dock.addView(button("+  Favoritos", this::addFavorite));
        for (App a : apps) appsRow.addView(appCard(a));
    }
    View appCard(App a) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8),dp(13),dp(8),dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(126),dp(104)); p.setMargins(0,0,dp(16),0); card.setLayoutParams(p);
        ImageView icon = new ImageView(this); icon.setImageDrawable(a.icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon,new LinearLayout.LayoutParams(dp(49),dp(49)));
        TextView title = text(a.name,12,WHITE); title.setGravity(Gravity.CENTER); title.setMaxLines(2);
        card.addView(title,new LinearLayout.LayoutParams(-1,0,1));
        focusable(card, () -> open(a.intent));
        card.setOnLongClickListener(v -> { editFavorite(a); return true; });
        return card;
    }
    void addFavorite() {
        if (apps.isEmpty()) { toast("Nenhum aplicativo encontrado."); return; }
        String[] names = new String[apps.size()]; for (int i=0;i<apps.size();i++) names[i] = apps.get(i).name;
        new AlertDialog.Builder(this).setTitle("Adicionar ao dock").setItems(names,(d,i) -> {
            ArrayList<String> f = favorites(); if (!f.contains(apps.get(i).pkg)) f.add(apps.get(i).pkg);
            saveFavorites(f); renderApps();
        }).setNegativeButton("Voltar",null).show();
    }
    void editFavorite(App a) {
        ArrayList<String> f = favorites(); boolean exists = f.contains(a.pkg);
        String[] choices = exists ? new String[]{"Mover para a esquerda","Mover para a direita","Remover do dock","Abrir"} : new String[]{"Adicionar ao dock","Abrir"};
        new AlertDialog.Builder(this).setTitle(a.name).setItems(choices,(d,which) -> {
            if (!exists) { if (which==0) { f.add(a.pkg); saveFavorites(f); renderApps(); } else open(a.intent); return; }
            int index = f.indexOf(a.pkg);
            if (which==0 && index>0) Collections.swap(f,index,index-1);
            if (which==1 && index<f.size()-1) Collections.swap(f,index,index+1);
            if (which==2) f.remove(a.pkg);
            if (which==3) { open(a.intent); return; }
            saveFavorites(f); renderApps();
        }).setNegativeButton("Voltar",null).show();
    }
    void open(Intent intent) {
        try { startActivity(intent); }
        catch (Exception e) { toast("Não foi possível abrir este conteúdo. Abra o aplicativo pelo dock."); }
    }
    void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    String col(Cursor c, String name) {
        int i = c.getColumnIndex(name); return i<0 || c.isNull(i) ? "" : c.getString(i);
    }
    int num(Cursor c, String name) {
        int i = c.getColumnIndex(name); return i<0 || c.isNull(i) ? 0 : c.getInt(i);
    }
    void loadPrograms() {
        if (io.isShutdown()) return;
        final int token = ++generation;
        io.execute(() -> {
            ArrayList<Program> rows = new ArrayList<>(); String error = "";
            try (Cursor c = getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"),null,null,null,null)) {
                if (c!=null) while (c.moveToNext() && rows.size()<24) {
                    if (c.getColumnIndex("browsable")>=0 && num(c,"browsable")==0) continue;
                    Program p = new Program(); p.title=col(c,"title"); p.intent=col(c,"intent_uri"); p.pkg=col(c,"package_name");
                    if (p.title.isEmpty() || p.intent.isEmpty()) continue;
                    p.description=col(c,"short_description"); p.art=col(c,"poster_art_uri");
                    p.position=num(c,"last_playback_position_millis"); p.duration=num(c,"duration_millis"); rows.add(p);
                }
            } catch (SecurityException e) { error = "A TV não permitiu ler o Continuar assistindo."; }
              catch (Exception e) { error = "Continuar assistindo indisponível nesta TV."; }
            final String msg = error;
            ui.post(() -> { if (isDestroyed() || token!=generation) return; renderPrograms(rows,msg); });
        });
    }
    void requestProgramsPermission() {
        if (!prefs.getBoolean("askedPermission",false) || shouldShowRequestPermissionRationale("android.permission.READ_TV_LISTINGS")) {
            prefs.edit().putBoolean("askedPermission",true).apply();
            requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"},42);
        } else open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code==42) loadPrograms();
    }
    void renderPrograms(ArrayList<Program> rows, String error) {
        nextRow.removeAllViews();
        nextHint.setText(!error.isEmpty() ? error : rows.isEmpty()
            ? "Os títulos aparecem quando os aplicativos os compartilham com o Android TV. Abrir um app abaixo não retoma um título específico."
            : "Conteúdos compartilhados pelos seus aplicativos");
        for (Program p : rows) nextRow.addView(programCard(p));
        if (rows.isEmpty()) {
            if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED)
                nextRow.addView(button("Permitir acesso aos conteúdos", this::requestProgramsPermission));
            for (App a : apps) if (a.pkg.toLowerCase(Locale.ROOT).contains("youtube") || a.pkg.toLowerCase(Locale.ROOT).contains("nuvio")) {
                nextRow.addView(button("Abrir "+a.name, () -> open(a.intent)));
            }
            if (nextRow.getChildCount()==0) nextRow.addView(button("Atualizar conteúdos", this::loadPrograms));
        }
    }
    View programCard(Program p) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8),dp(8),dp(8),dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(228),dp(186)); lp.setMargins(0,0,dp(16),0); card.setLayoutParams(lp);
        ImageView art = new ImageView(this); art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0xFF172B3D); card.addView(art,new LinearLayout.LayoutParams(-1,dp(110))); loadImage(p.art,art);
        TextView title = text(p.title,14,WHITE); title.setMaxLines(2); title.setPadding(dp(4),dp(6),dp(4),0);
        card.addView(title,new LinearLayout.LayoutParams(-1,0,1));
        if (p.duration>0) {
            ProgressBar bar = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000); bar.setProgress((int)Math.min(1000,Math.max(0,(long)p.position*1000/p.duration)));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF8BE9D0));
            card.addView(bar,new LinearLayout.LayoutParams(-1,dp(4)));
        }
        focusable(card, () -> {
            try {
                Intent intent = Intent.parseUri(p.intent,Intent.URI_INTENT_SCHEME);
                intent.setSelector(null); intent.setComponent(null);
                if (!p.pkg.isEmpty()) intent.setPackage(p.pkg);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); open(intent);
            } catch (Exception e) { toast("Este conteúdo não possui um link válido."); }
        });
        return card;
    }
    void loadImage(String uri, ImageView target) {
        if (uri==null || uri.isEmpty()) return;
        Bitmap cached = images.get(uri); if (cached!=null) { target.setImageBitmap(cached); return; }
        target.setTag(uri);
        io.execute(() -> {
            try {
                byte[] bytes = readBytes(uri,3*1024*1024);
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
                if (opts.outWidth<=0 || opts.outHeight<=0) return;
                int sample=1; while (opts.outWidth/sample>800 || opts.outHeight/sample>500) sample*=2;
                opts.inSampleSize=sample; opts.inJustDecodeBounds=false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts); if(bitmap==null) return;
                images.put(uri,bitmap); ui.post(() -> { if (!isDestroyed() && uri.equals(target.getTag())) target.setImageBitmap(bitmap); });
            } catch (Exception ignored) {}
        });
    }
    byte[] readBytes(String value, int max) throws Exception {
        Uri uri = Uri.parse(value); HttpURLConnection con = null; InputStream in;
        if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) in=getContentResolver().openInputStream(uri);
        else {
            if (!"https".equals(uri.getScheme())) throw new IOException("HTTPS obrigatório");
            con=(HttpURLConnection)new URL(value).openConnection(); con.setConnectTimeout(7000); con.setReadTimeout(7000);
            if (con.getResponseCode()!=200) { con.disconnect(); throw new IOException("HTTP"); }
            in=con.getInputStream();
        }
        try (InputStream source=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            if (source==null) throw new IOException("Sem dados"); byte[] buf=new byte[8192]; int n;
            while ((n=source.read(buf))!=-1) { if (out.size()+n>max) throw new IOException("Limite"); out.write(buf,0,n); }
            return out.toByteArray();
        } finally { if(con!=null) con.disconnect(); }
    }
    JSONObject json(String url) throws Exception { return new JSONObject(new String(readBytes(url,512*1024),java.nio.charset.StandardCharsets.UTF_8)); }
    void chooseCity() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("Cidade, por exemplo: Campinas");
        input.setText(prefs.getString("city",""));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Cidade do clima").setView(input)
            .setPositiveButton("Buscar",null).setNegativeButton("Voltar",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String query=input.getText().toString().trim(); if(query.length()<2) { input.setError("Digite a cidade"); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            io.execute(() -> {
                try {
                    JSONObject data=json("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(query,"UTF-8")+"&count=6&language=pt&format=json");
                    JSONArray results=data.optJSONArray("results"); if(results==null || results.length()==0) throw new IOException("Cidade não encontrada");
                    String[] labels=new String[results.length()];
                    for(int i=0;i<results.length();i++) { JSONObject city=results.getJSONObject(i); labels[i]=city.getString("name")+" · "+city.optString("admin1")+" · "+city.optString("country"); }
                    ui.post(() -> {
                        if(isDestroyed())return; dialog.dismiss();
                        new AlertDialog.Builder(this).setTitle("Selecione sua cidade").setItems(labels,(d2,index) -> {
                            try {
                                JSONObject city=results.getJSONObject(index);
                                prefs.edit().putString("city",city.getString("name")).putString("lat",city.getString("latitude")).putString("lon",city.getString("longitude")).remove("weather").apply();
                                refreshWeather();
                            } catch(Exception e) { toast("Não foi possível selecionar a cidade."); }
                        }).setNegativeButton("Voltar",null).show();
                    });
                } catch(Exception e) { ui.post(() -> { if(isDestroyed())return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); input.setError("Cidade não encontrada ou sem conexão. Tente novamente."); }); }
            });
        })); dialog.show();
    }
    void refreshWeather() {
        String city=prefs.getString("city","");
        if(city.isEmpty()) { weather.setText("Escolher cidade  ›"); return; }
        String old=prefs.getString("weather","");
        weather.setText(old.isEmpty() ? city+" · Atualizando…" : old+"\n"+weatherAge());
        if(weatherLoading || io.isShutdown())return; weatherLoading=true;
        final String lat=prefs.getString("lat",""), lon=prefs.getString("lon","");
        io.execute(() -> {
            try {
                JSONObject data=json("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
                JSONObject current=data.getJSONObject("current"), daily=data.getJSONObject("daily");
                String display=city+"   "+Math.round(current.getDouble("temperature_2m"))+"°   "+condition(current.getInt("weather_code"))
                    +"   ↑"+Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))+"° ↓"+Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))+"°";
                ui.post(() -> {
                    weatherLoading=false; if(isDestroyed())return;
                    if(!lat.equals(prefs.getString("lat","")) || !lon.equals(prefs.getString("lon",""))) { refreshWeather(); return; }
                    prefs.edit().putString("weather",display).putLong("weatherTime",System.currentTimeMillis()).apply();
                    weather.setText(display+"\n"+weatherAge());
                });
            } catch(Exception e) { ui.post(() -> {
                weatherLoading=false; if(isDestroyed())return;
                if(!lat.equals(prefs.getString("lat","")) || !lon.equals(prefs.getString("lon",""))) { refreshWeather(); return; }
                weather.setText(old.isEmpty() ? city+" · Clima indisponível" : old+"\nSem conexão · "+weatherAge());
            }); }
        });
    }
    String weatherAge() {
        long time=prefs.getLong("weatherTime",0);
        return "Open-Meteo · "+(time==0 ? "sem atualização" : "atualizado em "+new SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new Date(time)));
    }
    String condition(int code) {
        if(code==0) return "Céu limpo"; if(code<=3)return "Parcialmente nublado"; if(code<=48)return "Nevoeiro";
        if(code<=67)return "Chuva"; if(code<=77)return "Neve"; if(code<=82)return "Pancadas de chuva";
        if(code<=86)return "Neve"; return "Trovoadas";
    }
    void help() {
        new AlertDialog.Builder(this).setTitle("Aurora TV").setMessage(
            "Use as setas e OK para navegar. Segure OK sobre um aplicativo para editar o dock.\n\n"+
            "O clima usa a cidade escolhida, sem solicitar localização. Atualiza a cada 30 minutos enquanto a tela está aberta.\n\n"+
            "Continuar assistindo depende dos títulos publicados pelos aplicativos no Android TV. YouTube e Nuvio só aparecem se disponibilizarem esses dados.\n\n"+
            "Para usar como início, pressione HOME e selecione Aurora TV, se a sua TV permitir.\n\n"+
            "Clima: Open-Meteo (CC BY 4.0). Dados geográficos: GeoNames.").setPositiveButton("Entendi",null).show();
    }
    class AuroraBackground extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        AuroraBackground(Context c) { super(c); }
        protected void onDraw(Canvas canvas) {
            float w=getWidth(), h=getHeight();
            paint.setShader(new LinearGradient(0,0,w,h,new int[]{0xFF071521,0xFF18334B,0xFF102332},null,Shader.TileMode.CLAMP)); canvas.drawRect(0,0,w,h,paint);
            paint.setShader(new RadialGradient(w*.76f,h*.08f,w*.68f,new int[]{0x806DC9AF,0x00328B95},null,Shader.TileMode.CLAMP)); canvas.drawRect(0,0,w,h,paint);
            paint.setShader(new RadialGradient(w*.15f,h*.8f,w*.65f,new int[]{0x603C447F,0x0012223A},null,Shader.TileMode.CLAMP)); canvas.drawRect(0,0,w,h,paint);
            paint.setShader(null);
        }
    }
}

