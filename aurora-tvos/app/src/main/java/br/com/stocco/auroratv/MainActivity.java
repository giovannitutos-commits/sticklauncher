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
    TextView weather, clock, nextHint, weatherCity, weatherDetails, weatherStamp;
    LinearLayout weatherHero;
    WeatherGlyph weatherGlyph;
    Bitmap scene, frostedScene;
    int screenWidth, screenHeight;
    ScrollView scroll;
    final ArrayList<App> apps = new ArrayList<>();
    boolean resumed, weatherLoading;
    int generation;
    static final int WHITE = Color.rgb(244,247,252), MUTED = Color.rgb(177,194,210);
    static class App {
        String name, pkg;
        Intent intent;
        Drawable icon, banner;
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
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        makeScene();
        FrameLayout root = new FrameLayout(this);
        ImageView wallpaper = new ImageView(this);
        wallpaper.setImageBitmap(scene); wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1,-1));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setBackgroundColor(Color.TRANSPARENT);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false); body.setClipToPadding(false);
        scroll.addView(body); root.addView(scroll); setContentView(root);

        int heroHeight = Math.max(dp(400),screenHeight-dp(38));
        FrameLayout hero = new FrameLayout(this); hero.setClipChildren(false); hero.setClipToPadding(false);
        body.addView(hero,new LinearLayout.LayoutParams(-1,heroHeight));

        weatherHero = new LinearLayout(this); weatherHero.setOrientation(LinearLayout.VERTICAL);
        weatherHero.setPadding(dp(18),dp(12),dp(18),dp(14));
        weatherCity = text("Seu clima",21,WHITE);
        weatherCity.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        weatherHero.addView(weatherCity);
        LinearLayout temperatureLine = new LinearLayout(this); temperatureLine.setGravity(Gravity.CENTER_VERTICAL);
        weather = text("—°",74,WHITE); weather.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        weather.setIncludeFontPadding(false);
        temperatureLine.addView(weather,new LinearLayout.LayoutParams(-2,dp(90)));
        weatherGlyph = new WeatherGlyph(this);
        LinearLayout.LayoutParams glyphParams=new LinearLayout.LayoutParams(dp(76),dp(76)); glyphParams.leftMargin=dp(16);
        temperatureLine.addView(weatherGlyph,glyphParams); weatherHero.addView(temperatureLine);
        weatherDetails = text("Escolha sua cidade",16,WHITE); weatherHero.addView(weatherDetails);
        weatherStamp = text("OK para configurar  ·  Open-Meteo",10,0xCCFFFFFF);
        weatherStamp.setPadding(0,dp(9),0,0); weatherHero.addView(weatherStamp);
        FrameLayout.LayoutParams weatherParams=new FrameLayout.LayoutParams(dp(470),-2,Gravity.TOP|Gravity.LEFT);
        weatherParams.setMargins(dp(30),dp(20),0,0); hero.addView(weatherHero,weatherParams);
        weatherHero.setFocusable(true); weatherHero.setClickable(true); weatherHero.setOnClickListener(v->chooseCity());
        weatherHero.setOnFocusChangeListener((v,focus)->{
            v.setBackground(focus ? new GlassBackground(v,true,22) : null);
        });

        LinearLayout topRight = new LinearLayout(this); topRight.setGravity(Gravity.CENTER_VERTICAL);
        clock=text("",20,WHITE); clock.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        clock.setPadding(dp(12),dp(12),dp(16),dp(12)); topRight.addView(clock);
        TextView settings=button("⚙",()->open(new Intent(Settings.ACTION_SETTINGS)));
        settings.setTextSize(22); topRight.addView(settings);
        FrameLayout.LayoutParams clockParams=new FrameLayout.LayoutParams(-2,dp(56),Gravity.TOP|Gravity.RIGHT);
        clockParams.setMargins(0,dp(24),dp(30),0); hero.addView(topRight,clockParams);

        TextView hint=text("SEUS FAVORITOS",10,0xCCFFFFFF);
        hint.setLetterSpacing(.18f);
        FrameLayout.LayoutParams hintParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);
        hintParams.setMargins(dp(46),0,0,dp(142)); hero.addView(hint,hintParams);
        FrameLayout dockSurface=new FrameLayout(this); dockSurface.setClipChildren(false); dockSurface.setClipToPadding(false);
        dockSurface.setBackground(new GlassBackground(dockSurface,false,28));
        dockSurface.setPadding(dp(8),dp(5),dp(8),dp(5));
        dock=row(); dock.setPadding(dp(10),dp(10),dp(10),dp(10));
        dockSurface.addView(scroller(dock),new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams dockParams=new FrameLayout.LayoutParams(-1,dp(125),Gravity.BOTTOM);
        dockParams.setMargins(dp(38),0,dp(38),dp(10)); hero.addView(dockSurface,dockParams);

        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(42),0,dp(42),dp(30)); content.setBackground(new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00101C29,0xD9091725,0xF5081521}));
        body.addView(content);
        LinearLayout pageBody=body; body=content;
        section("Continuar assistindo", "Seus próximos episódios");
        nextRow=row(); body.addView(scroller(nextRow));
        nextHint=text("Buscando conteúdos compartilhados pelos aplicativos…",12,MUTED);
        nextHint.setPadding(dp(4),dp(4),dp(4),dp(10)); body.addView(nextHint);
        section("Todos os aplicativos","Segure OK para editar o dock");
        appsRow=row(); body.addView(scroller(appsRow));
        LinearLayout controls=row(); controls.setPadding(0,dp(18),0,dp(8));
        controls.addView(button("Clima e cidade",this::chooseCity));
        controls.addView(button("Organizar favoritos",this::addFavorite));
        controls.addView(button("Ajuda",this::help)); body.addView(controls);
        body=pageBody;
        clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
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
    @Override public void onBackPressed() { scroll.smoothScrollTo(0,0); if (dock.getChildCount()>0) dock.getChildAt(0).requestFocus(); }
    TextView text(String value, float size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern"); v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); return v;
    }
    GradientDrawable panel(boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            focused ? new int[]{0xFF486278,0xFF263C50} : new int[]{0xD92B3C50,0xCF1B2A3C});
        d.setCornerRadius(dp(17)); d.setStroke(dp(focused ? 2 : 1), focused ? 0xFFADF8E9 : 0x30FFFFFF); return d;
    }
    void focusable(View v, Runnable click) {
        v.setFocusable(true); v.setClickable(true); v.setBackground(new GlassBackground(v,false,17));
        v.setOnClickListener(w -> click.run());
        v.setOnFocusChangeListener((w, f) -> {
            w.setBackground(new GlassBackground(w,f,17)); w.setElevation(dp(f ? 12 : 2));
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
            try { a.banner=pm.getApplicationBanner(a.pkg); } catch(Exception ignored) {}
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
        TextView plus=button("+",this::addFavorite); plus.setTextSize(30);
        LinearLayout.LayoutParams plusParams=new LinearLayout.LayoutParams(dp(65),dp(94)); plusParams.setMargins(dp(2),0,dp(4),0); plus.setLayoutParams(plusParams); dock.addView(plus);
        for (App a : apps) appsRow.addView(appCard(a));
    }
    class AppTile extends LinearLayout {
        boolean holding, held; int pressedCode;
        final Runnable longPress=()->{ if(holding && hasFocus() && isAttachedToWindow()) { held=true; performLongClick(); } };
        AppTile() { super(MainActivity.this); }
        void cancelHold() { holding=false; setPressed(false); ui.removeCallbacks(longPress); }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            int code=event.getKeyCode();
            if(code!=KeyEvent.KEYCODE_DPAD_CENTER && code!=KeyEvent.KEYCODE_ENTER &&
               code!=KeyEvent.KEYCODE_NUMPAD_ENTER && code!=KeyEvent.KEYCODE_BUTTON_A) return super.dispatchKeyEvent(event);
            if(event.getAction()==KeyEvent.ACTION_DOWN) {
                if(!holding && event.getRepeatCount()==0) {
                    holding=true; held=false; pressedCode=code; setPressed(true); ui.postDelayed(longPress,600);
                }
                return true;
            }
            if(event.getAction()==KeyEvent.ACTION_UP) {
                boolean click=holding && !held && code==pressedCode && !event.isCanceled() && hasFocus();
                cancelHold(); if(click)performClick(); return true;
            }
            return true;
        }
        @Override protected void onFocusChanged(boolean focus,int direction,Rect previous) {
            super.onFocusChanged(focus,direction,previous); if(!focus)cancelHold();
        }
        @Override protected void onDetachedFromWindow() { cancelHold(); super.onDetachedFromWindow(); }
    }
    View appCard(App a) {
        AppTile card=new AppTile(); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4),dp(5),dp(4),dp(3));
        int tileWidth=Math.min(dp(138),Math.max(dp(110),(screenWidth-dp(120))/6));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(tileWidth,dp(94));
        p.setMargins(0,0,dp(10),0); card.setLayoutParams(p);
        FrameLayout picture=new FrameLayout(this);
        GradientDrawable color=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{appTint(a.pkg),0xFF172538});
        color.setCornerRadius(dp(13)); picture.setBackground(color); picture.setClipToOutline(true);
        ImageView icon=new ImageView(this);
        if(a.banner!=null) {
            icon.setImageDrawable(a.banner); icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.addView(icon,new FrameLayout.LayoutParams(-1,-1));
        } else {
            icon.setImageDrawable(a.icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            picture.addView(icon,new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.CENTER));
        }
        card.addView(picture,new LinearLayout.LayoutParams(-1,dp(64)));
        TextView title=text(a.name,11,WHITE); title.setGravity(Gravity.CENTER); title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); title.setMarqueeRepeatLimit(-1);
        card.addView(title,new LinearLayout.LayoutParams(-1,dp(21)));
        card.setContentDescription(a.name+", segure OK para organizar");
        card.setFocusable(true); card.setClickable(true);
        card.setOnClickListener(v->open(a.intent));
        card.setOnFocusChangeListener((v,focused)->{
            title.setSelected(focused);
            v.setBackground(focused ? new GlassBackground(v,true,18) : null);
            v.setElevation(dp(focused?12:0));
            v.animate().scaleX(focused?1.055f:1).scaleY(focused?1.055f:1).setDuration(150).start();
        });
        card.setOnLongClickListener(v->{editFavorite(a);return true;});
        return card;
    }
    int appTint(String pkg) {
        String p=pkg.toLowerCase(Locale.ROOT);
        if(p.contains("youtube"))return 0xFFD52235;
        if(p.contains("nuvio"))return 0xFF6E39B6;
        if(p.contains("netflix"))return 0xFF802633;
        if(p.contains("amazon"))return 0xFF137CB8;
        if(p.contains("spotify"))return 0xFF219F72;
        return 0xFF36566C;
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
                                prefs.edit().putString("city",city.getString("name")).putString("lat",city.getString("latitude")).putString("lon",city.getString("longitude")).remove("weather").remove("weatherGlass").apply();
                                refreshWeather();
                            } catch(Exception e) { toast("Não foi possível selecionar a cidade."); }
                        }).setNegativeButton("Voltar",null).show();
                    });
                } catch(Exception e) { ui.post(() -> { if(isDestroyed())return; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); input.setError("Cidade não encontrada ou sem conexão. Tente novamente."); }); }
            });
        })); dialog.show();
    }
    void showWeather(String city, JSONObject data, boolean offline) {
        weatherCity.setText(city.isEmpty() ? "Seu clima" : city);
        if(data==null) {
            weather.setText("—°"); weatherDetails.setText(city.isEmpty() ? "Escolha sua cidade" : "Clima indisponível");
            weatherStamp.setText("OK para configurar · Open-Meteo"); return;
        }
        weather.setText(Math.round(data.optDouble("temperature"))+"°");
        int code=data.optInt("code"); weatherGlyph.code=code; weatherGlyph.invalidate();
        weatherDetails.setText(condition(code)+"   ↑"+Math.round(data.optDouble("max"))+"°  ↓"+Math.round(data.optDouble("min"))+"°");
        weatherStamp.setText((offline ? "Sem conexão · " : "")+weatherAge()+" · OK para alterar");
    }
    void refreshWeather() {
        String city=prefs.getString("city","");
        JSONObject cached=null;
        try { cached=new JSONObject(prefs.getString("weatherGlass","")); } catch(Exception ignored) {}
        showWeather(city,cached,false);
        if(city.isEmpty() || weatherLoading || io.isShutdown())return;
        weatherLoading=true;
        final JSONObject old=cached;
        final String lat=prefs.getString("lat",""),lon=prefs.getString("lon","");
        io.execute(()->{
            try {
                JSONObject response=json("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
                JSONObject current=response.getJSONObject("current"), daily=response.getJSONObject("daily");
                JSONObject data=new JSONObject().put("temperature",current.getDouble("temperature_2m"))
                    .put("code",current.getInt("weather_code")).put("max",daily.getJSONArray("temperature_2m_max").getDouble(0))
                    .put("min",daily.getJSONArray("temperature_2m_min").getDouble(0));
                ui.post(()->{
                    weatherLoading=false; if(isDestroyed())return;
                    if(!lat.equals(prefs.getString("lat","")) || !lon.equals(prefs.getString("lon",""))) {refreshWeather();return;}
                    prefs.edit().putString("weatherGlass",data.toString()).putLong("weatherTime",System.currentTimeMillis()).apply();
                    showWeather(city,data,false);
                });
            } catch(Exception e) {
                ui.post(()->{
                    weatherLoading=false; if(isDestroyed())return;
                    if(!lat.equals(prefs.getString("lat","")) || !lon.equals(prefs.getString("lon",""))) {refreshWeather();return;}
                    showWeather(city,old,true);
                });
            }
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
    void makeScene() {
        int w=Math.min(screenWidth,1920),h=Math.max(1,Math.round((float)screenHeight*w/screenWidth));
        scene=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(scene); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0,0,w,h,new int[]{0xFF385967,0xFF789298,0xFFDBC5A5,0xFF203D4E},new float[]{0,.35f,.65f,1},Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,p);
        p.setShader(new RadialGradient(w*.72f,h*.28f,w*.48f,new int[]{0xA8FFF0CE,0x00FFF0CE},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,p);
        for(int layer=0;layer<5;layer++) {
            Path path=new Path(); float base=h*(.51f+layer*.095f);
            path.moveTo(-w*.1f,h); path.lineTo(-w*.1f,base);
            for(int i=0;i<=12;i++) {
                float x=w*(i/11f);
                float y=base-(float)(Math.sin(i*.83+layer*1.2)*.5+.5)*h*(.11f+layer*.017f);
                path.lineTo(x,y);
            }
            path.lineTo(w*1.1f,h); path.close();
            p.setShader(null);
            int[] colors={0xFF91A5A5,0xFF708D93,0xFF496B79,0xFF274C60,0xFF123244};
            p.setColor(colors[layer]); canvas.drawPath(path,p);
        }
        p.setShader(new LinearGradient(0,0,w*.72f,0,new int[]{0xA8102535,0x00102535},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,p);
        p.setShader(new LinearGradient(0,h*.65f,0,h,new int[]{0x00102030,0xB8091725},null,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,p); p.setShader(null);

        int bw=160,bh=Math.max(1,Math.round(160f*h/w));
        Bitmap tiny=Bitmap.createScaledBitmap(scene,bw,bh,true);
        int[] pixels=new int[bw*bh],temp=new int[bw*bh]; tiny.getPixels(pixels,0,bw,0,0,bw,bh);
        for(int pass=0;pass<3;pass++) {
            for(int y=0;y<bh;y++)for(int x=0;x<bw;x++) {
                int red=0,green=0,blue=0,count=0;
                for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++) {
                    int color=pixels[Math.max(0,Math.min(bh-1,y+dy))*bw+Math.max(0,Math.min(bw-1,x+dx))];
                    red+=Color.red(color);green+=Color.green(color);blue+=Color.blue(color);count++;
                }
                temp[y*bw+x]=Color.rgb(red/count,green/count,blue/count);
            }
            int[] swap=pixels;pixels=temp;temp=swap;
        }
        frostedScene=Bitmap.createBitmap(pixels,bw,bh,Bitmap.Config.ARGB_8888);tiny.recycle();
    }
    class GlassBackground extends Drawable {
        final View host; final boolean focused; final float radius;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final int[] location=new int[2];
        GlassBackground(View host,boolean focused,float radius) {this.host=host;this.focused=focused;this.radius=dp(radius);}
        public void draw(Canvas canvas) {
            Rect b=getBounds(); RectF rect=new RectF(b); Path clip=new Path(); clip.addRoundRect(rect,radius,radius,Path.Direction.CW);
            canvas.save();canvas.clipPath(clip);
            host.getLocationOnScreen(location);
            paint.setShader(null);paint.setAlpha(225);
            canvas.drawBitmap(frostedScene,null,new RectF(-location[0],-location[1],screenWidth-location[0],screenHeight-location[1]),paint);
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0,b.top,0,b.bottom,new int[]{focused?0x70FFFFFF:0x50FFFFFF,0x18273747,0x40203A47},new float[]{0,.5f,1},Shader.TileMode.CLAMP));
            canvas.drawRect(rect,paint);
            paint.setShader(new LinearGradient(b.left,b.top,b.right,b.bottom,new int[]{0x34FFFFFF,0x00FFFFFF,0x20FFFFFF},null,Shader.TileMode.CLAMP));
            canvas.drawRect(rect,paint);paint.setShader(null);
            paint.setColor(focused?0x16FFFFFF:0x09071020);canvas.drawRect(rect,paint);
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(focused?2:1));
            paint.setShader(new LinearGradient(b.left,b.top,b.right,b.bottom,new int[]{focused?0xFFFFFFFF:0xAFFFFFFF,0x25FFFFFF,0x75FFFFFF},null,Shader.TileMode.CLAMP));
            rect.inset(dp(1),dp(1));canvas.drawRoundRect(rect,radius,radius,paint);
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);
        }
        public void setAlpha(int alpha) {}
        public void setColorFilter(ColorFilter filter) {}
        public int getOpacity() {return PixelFormat.TRANSLUCENT;}
    }
    class WeatherGlyph extends View {
        int code=0; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        WeatherGlyph(Context c) {super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas canvas) {
            float size=Math.min(getWidth(),getHeight()),cx=size*.43f,cy=size*.42f;
            if(code<=3) {
                paint.setColor(0xFFFFE4A3);canvas.drawCircle(cx,cy,size*.19f,paint);
                paint.setStrokeWidth(size*.026f);paint.setStrokeCap(Paint.Cap.ROUND);
                for(int i=0;i<8;i++) {
                    double angle=i*Math.PI/4;
                    canvas.drawLine(cx+(float)Math.cos(angle)*size*.27f,cy+(float)Math.sin(angle)*size*.27f,
                        cx+(float)Math.cos(angle)*size*.33f,cy+(float)Math.sin(angle)*size*.33f,paint);
                }
            }
            if(code>0) {
                paint.setColor(0xFFF0F7FD);
                canvas.drawCircle(size*.48f,size*.58f,size*.17f,paint);
                canvas.drawCircle(size*.65f,size*.5f,size*.22f,paint);
                canvas.drawCircle(size*.81f,size*.61f,size*.14f,paint);
                canvas.drawRoundRect(new RectF(size*.34f,size*.58f,size*.94f,size*.75f),size*.08f,size*.08f,paint);
                if(code>=51) {
                    paint.setColor(0xFFB3E7FF);paint.setStrokeWidth(size*.035f);
                    for(int i=0;i<3;i++)canvas.drawLine(size*(.48f+i*.14f),size*.83f,size*(.44f+i*.14f),size*.93f,paint);
                }
            }
        }
    }
}
