package br.com.mi.atv.launcher;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Lightweight native Android TV home. Coordinates use a 960 x 540 logical stage. */
public class MainActivity extends Activity {
    private static final String TV_PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri WATCH_URI = Uri.parse("content://android.media.tv/watch_next_program");
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private SharedPreferences prefs;
    private FrameLayout stage;
    private ScrollView mainScroll;
    private LinearLayout content;
    private TextView weather;
    private View gear;
    private View firstTile;
    private ArrayList<App> apps = new ArrayList<>();
    private ArrayList<Program> programs = new ArrayList<>();
    private ArrayList<String> pinned = new ArrayList<>();
    private String selectedKey = "";
    private String providerStatus = "Carregando conteúdos…";
    private boolean loading;
    private boolean observing;
    private int uiGeneration;
    private int weatherGeneration;
    private int citySearchGeneration;
    private Bitmap wallpaper;
    private final Runnable refreshPrograms = this::loadData;
    private final Runnable weatherTimer = new Runnable() {
        @Override public void run() { updateWeather(false); handler.postDelayed(this, 30 * 60 * 1000); }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            handler.removeCallbacks(refreshPrograms); handler.postDelayed(refreshPrograms, 800);
        }
    };
    private static final class App {
        String pkg, name; Drawable banner, icon;
    }
    private static final class Program {
        String id, pkg, source, title, art, intent;
        long duration, position, last;
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tvos_launcher_prefs", MODE_PRIVATE);
        readPinned();
        BitmapFactory.Options opt = new BitmapFactory.Options(); opt.inPreferredConfig = Bitmap.Config.RGB_565;
        wallpaper = BitmapFactory.decodeResource(getResources(), R.drawable.wallpaper, opt);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(5894);
        loadData();
        handler.removeCallbacks(weatherTimer); handler.post(weatherTimer);
        if (!observing) {
            try { getContentResolver().registerContentObserver(WATCH_URI, true, observer); observing = true; }
            catch (RuntimeException ignored) { /* Query status is shown in Settings. */ }
        }
    }
    @Override protected void onPause() {
        super.onPause(); handler.removeCallbacks(weatherTimer); handler.removeCallbacks(refreshPrograms);
        if (observing) { getContentResolver().unregisterContentObserver(observer); observing = false; }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); io.shutdownNow(); images.evictAll(); super.onDestroy();
    }
    private void readPinned() {
        String order = prefs.getString("pinned_list_order", null);
        if (order != null) {
            for (String p : order.split(",")) if (!p.isEmpty() && !pinned.contains(p)) pinned.add(p);
        }
        if (order == null) {
            Set<String> old = prefs.getStringSet("pinned_pkgs", null);
            if (old != null) pinned.addAll(old);
            else pinned.addAll(Arrays.asList("com.nuvio.tv", "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.spotify.tv.android"));
        }
    }
    private void savePinned() { prefs.edit().putString("pinned_list_order", TextUtils.join(",", pinned)).apply(); }
    private void loadData() {
        if (loading || io.isShutdown()) return;
        loading = true;
        io.execute(() -> {
            ArrayList<App> found = discoverApps();
            ArrayList<Program> next = discoverPrograms(found);
            handler.post(() -> {
                loading = false; if (!alive()) return;
                apps = found; programs = next;
                if (!prefs.contains("pinned_list_order")) {
                    pinned.removeIf(p -> findApp(p) == null);
                    if (pinned.isEmpty()) for (int i=0; i<Math.min(6,apps.size()); i++) pinned.add(apps.get(i).pkg);
                    savePinned();
                }
                render(); updateWeather(false);
            });
        });
    }
    private App findApp(String pkg) { for (App a:apps) if (a.pkg.equals(pkg)) return a; return null; }
    private ArrayList<App> discoverApps() {
        PackageManager pm = getPackageManager(); LinkedHashMap<String,App> found = new LinkedHashMap<>();
        Set<String> hidden = new HashSet<>(Arrays.asList(getPackageName(),"com.mitv.tvhome","com.google.android.tvlauncher","com.google.android.tv","com.android.tv.settings"));
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat),0)) {
                String pkg = r.activityInfo.packageName;
                if (hidden.contains(pkg) || found.containsKey(pkg)) continue;
                App a=new App(); a.pkg=pkg; a.name=r.loadLabel(pm).toString();
                try { a.banner=r.activityInfo.loadBanner(pm); if(a.banner==null) a.banner=pm.getApplicationBanner(pkg); } catch(RuntimeException|PackageManager.NameNotFoundException ignored) {}
                try { a.icon=r.loadIcon(pm); } catch(RuntimeException ignored) {}
                found.put(pkg,a);
            }
        }
        ArrayList<App> list=new ArrayList<>(found.values()); list.sort((a,b)->a.name.compareToIgnoreCase(b.name)); return list;
    }
    private static String str(Cursor c,String key) { int i=c.getColumnIndex(key); return i<0 || c.isNull(i)?"":c.getString(i); }
    private static long number(Cursor c,String key) { int i=c.getColumnIndex(key); return i<0 || c.isNull(i)?0:c.getLong(i); }
    private boolean wantedSource(String pkg, ArrayList<App> installed) {
        if (pkg.equals("com.google.android.youtube.tv") || pkg.equals("com.google.android.youtube")) return true;
        for(App a:installed) if(a.pkg.equals(pkg)) return a.pkg.toLowerCase(Locale.ROOT).contains("nuvio") || a.name.toLowerCase(Locale.ROOT).contains("nuvio");
        return false;
    }
    private ArrayList<Program> discoverPrograms(ArrayList<App> installed) {
        ArrayList<Program> result=new ArrayList<>();
        if(checkSelfPermission(TV_PERMISSION)!=PackageManager.PERMISSION_GRANTED) { providerStatus="Permissão de listagens de TV não concedida."; return result; }
        try(Cursor c=getContentResolver().query(WATCH_URI,null,null,null,null)) {
            if(c!=null) while(c.moveToNext()) {
                String pkg=str(c,"package_name");
                if(!wantedSource(pkg,installed) || str(c,"title").isEmpty()) continue;
                int browse=c.getColumnIndex("browsable"); if(browse>=0 && c.getInt(browse)==0) continue;
                Program p=new Program(); p.pkg=pkg; p.id="watch:"+pkg+":"+str(c,"_id");
                p.title=str(c,"title"); p.source=pkg.contains("youtube")?"YouTube":"Nuvio";
                p.art=str(c,"poster_art_uri"); p.intent=str(c,"intent_uri");
                p.duration=number(c,"duration_millis"); p.position=number(c,"last_playback_position_millis"); p.last=number(c,"last_engagement_time_utc_millis"); result.add(p);
            }
            result.sort((a,b)->Long.compare(b.last,a.last));
            if(result.size()>20) result=new ArrayList<>(result.subList(0,20));
            providerStatus=result.isEmpty()?"Nenhum cartão do Nuvio/YouTube publicado no Watch Next.":result.size()+" cartões do Nuvio/YouTube disponíveis.";
        } catch(SecurityException e) { providerStatus="O firmware bloqueou a leitura do Watch Next."; }
        catch(RuntimeException e) { providerStatus="Watch Next indisponível neste firmware."; }
        return result;
    }
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_PX,size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    private GradientDrawable panel(int color,int radius,int stroke) {
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);if(stroke!=0)g.setStroke(1,stroke);return g;
    }
    private void place(FrameLayout parent,View child,int x,int y,int w,int h) {
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h);lp.leftMargin=x;lp.topMargin=y;parent.addView(child,lp);
    }
    private void render() {
        int generation=++uiGeneration;
        String desiredKey=selectedKey;
        int oldScroll=mainScroll==null?0:mainScroll.getScrollY();
        FrameLayout host=new FrameLayout(this);host.setBackgroundColor(0xff07131d);
        stage=new FrameLayout(this);stage.setClipChildren(false);stage.setClipToPadding(false);
        FrameLayout.LayoutParams slp=new FrameLayout.LayoutParams(960,540,Gravity.CENTER);host.addView(stage,slp);stage.setPivotX(480);stage.setPivotY(270);
        host.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)-> { float scale=Math.min((r-l)/960f,(b-t)/540f);stage.setScaleX(scale);stage.setScaleY(scale); });
        ImageView bg=new ImageView(this);bg.setImageBitmap(wallpaper);bg.setScaleType(ImageView.ScaleType.CENTER_CROP);place(stage,bg,0,0,960,540);
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x28000000,0x10000000,0xdd070d15}));place(stage,shade,0,0,960,540);
        weather=text("☁  Configurar previsão\nEscolha sua cidade",16,Color.WHITE);weather.setGravity(Gravity.CENTER_VERTICAL);weather.setPadding(8,0,8,0);place(stage,weather,26,18,510,51);
        focus(weather,"weather",10,false);weather.setOnClickListener(v->cityDialog());
        TextClock clock=new TextClock(this);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(Color.WHITE);clock.setTextSize(TypedValue.COMPLEX_UNIT_PX,17);clock.setGravity(Gravity.CENTER);place(stage,clock,828,24,60,32);
        TextView settings=text("⚙",24,Color.WHITE);settings.setGravity(Gravity.CENTER);place(stage,settings,893,21,39,38);gear=settings;focus(settings,"settings",18,true);settings.setOnClickListener(v->settingsDialog());
        mainScroll=new ScrollView(this);mainScroll.setFillViewport(false);mainScroll.setClipChildren(false);mainScroll.setVerticalScrollBarEnabled(false);mainScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setClipChildren(false);content.setClipToPadding(false);content.setPadding(10,14,10,30);mainScroll.addView(content,new ScrollView.LayoutParams(-1,-2));place(stage,mainScroll,22,160,916,380);
        TextView heading=text("Continuar assistindo",23,Color.WHITE);heading.setTypeface(null,Typeface.BOLD);content.addView(heading,new LinearLayout.LayoutParams(-1,34));
        HorizontalScrollView watchScroll=horizontal();LinearLayout watchRow=row();watchRow.setPadding(4,6,4,10);watchScroll.addView(watchRow);
        content.addView(watchScroll,new LinearLayout.LayoutParams(-1,160));firstTile=null;
        HashSet<String> available=new HashSet<>();
        for(Program p:programs) { available.add(p.source);View card=watchCard(p,generation);addTile(watchRow,card,218,140,8);if(firstTile==null)firstTile=card; }
        // Honest fallback: launch the app, never pretend a history feed is available.
        for(String source:new String[]{"Nuvio","YouTube"}) if(!available.contains(source)) {
            App app=null;for(App a:apps) if((source.equals("Nuvio")&&(a.pkg.toLowerCase(Locale.ROOT).contains("nuvio")||a.name.toLowerCase(Locale.ROOT).contains("nuvio")))||(source.equals("YouTube")&&a.pkg.equals("com.google.android.youtube.tv"))) {app=a;break;}
            if(app!=null) {View card=fallbackCard(app,source);addTile(watchRow,card,218,140,8);if(firstTile==null)firstTile=card;}
        }
        if(watchRow.getChildCount()==0) {
            TextView empty=text("Abra o Nuvio ou o YouTube para começar.\nNenhum conteúdo de continuação disponível.",16,0xffd1d9e5);empty.setGravity(Gravity.CENTER_VERTICAL);empty.setPadding(14,10,10,10);watchRow.addView(empty,new LinearLayout.LayoutParams(600,140));
        }
        HorizontalScrollView dock=horizontal();dock.setBackground(panel(0xa82b303b,22,0x557c8b9a));dock.setPadding(12,8,12,8);
        LinearLayout dockRow=row();dockRow.setPadding(4,5,4,5);dock.addView(dockRow);LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-1,104);dlp.topMargin=12;content.addView(dock,dlp);
        for(String pkg:pinned) {App a=findApp(pkg);if(a!=null) {View tile=appTile(a);addTile(dockRow,tile,132,76,13);if(firstTile==null)firstTile=tile;}}
        if(dockRow.getChildCount()==0) {TextView empty=text("Segure OK em um app abaixo para fixar na dock",15,0xffd5dce8);empty.setGravity(Gravity.CENTER_VERTICAL);dockRow.addView(empty,new LinearLayout.LayoutParams(650,76));}
        TextView label=text("Mais aplicativos  ·  Segure OK para fixar ou remover",12,0xffc0c8d6);label.setPadding(4,10,0,6);content.addView(label,new LinearLayout.LayoutParams(-1,36));
        ArrayList<App> remaining=new ArrayList<>();for(App a:apps)if(!pinned.contains(a.pkg))remaining.add(a);
        for(int offset=0;offset<remaining.size();offset+=6) {
            LinearLayout rr=row();rr.setPadding(4,4,4,8);
            for(int j=0;j<6;j++) {if(offset+j<remaining.size())addTile(rr,appTile(remaining.get(offset+j)),132,76,13);else addTile(rr,new View(this),132,76,13);}
            content.addView(rr,new LinearLayout.LayoutParams(-1,92));
        }
        setContentView(host);mainScroll.post(()-> {
            if(generation!=uiGeneration)return;
            mainScroll.scrollTo(0,oldScroll);
            if(!restoreFocus(stage,desiredKey) && firstTile!=null)firstTile.requestFocus();
        });
    }
    private HorizontalScrollView horizontal() {
        HorizontalScrollView h=new HorizontalScrollView(this);h.setHorizontalScrollBarEnabled(false);h.setClipChildren(false);h.setClipToPadding(false);h.setFillViewport(false);return h;
    }
    private LinearLayout row() {LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setClipChildren(false);r.setClipToPadding(false);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private void addTile(LinearLayout parent,View child,int w,int h,int gap) {LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.rightMargin=gap;parent.addView(child,lp);}
    private boolean restoreFocus(View v,String key) {if(key.isEmpty())return false;if(key.equals(v.getTag())) {v.requestFocus();return true;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(restoreFocus(((ViewGroup)v).getChildAt(i),key))return true;return false;}
    private void focus(View v,String key,int radius,boolean tint) {
        v.setTag(key);v.setFocusable(true);v.setClickable(true);
        v.setOnFocusChangeListener((vv,has)-> {
            GradientDrawable outline=panel(has&&tint?0x554d6785:Color.TRANSPARENT,radius,has?Color.WHITE:0);
            if(has)outline.setStroke(2,Color.WHITE);v.setForeground(outline);
            v.animate().scaleX(has?1.035f:1f).scaleY(has?1.035f:1f).setDuration(120).start();
            if(has)selectedKey=key;
        });
    }
    private int appColor(String pkg) {if(pkg.contains("nuvio"))return 0xff6128a8;if(pkg.contains("youtube"))return 0xffa21823;if(pkg.contains("netflix"))return 0xff160e16;if(pkg.contains("amazon"))return 0xff096fbb;if(pkg.contains("disney"))return 0xff152657;if(pkg.contains("spotify"))return 0xff103c2a;return 0xff263e55;}
    private View appTile(App a) {
        FrameLayout card=new FrameLayout(this);card.setBackground(panel(appColor(a.pkg),12,0x336a7b91));card.setClipToOutline(true);
        if(a.banner!=null) {ImageView image=new ImageView(this);image.setImageDrawable(a.banner);image.setScaleType(ImageView.ScaleType.FIT_XY);card.addView(image,new FrameLayout.LayoutParams(-1,-1));}
        else {ImageView icon=new ImageView(this);icon.setImageDrawable(a.icon);place(card,icon,49,9,34,34);TextView label=text(a.name,12,Color.WHITE);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER);place(card,label,5,47,122,23);}
        card.setContentDescription(a.name+(pinned.contains(a.pkg)?", fixado":""));focus(card,"app:"+a.pkg,12,false);card.setOnClickListener(v->launchApp(a.pkg));card.setOnLongClickListener(v->{togglePin(a.pkg);return true;});return card;
    }
    private void togglePin(String pkg) {
        if(!pinned.remove(pkg))pinned.add(pkg);savePinned();render();updateWeather(false);
        Toast.makeText(this,pinned.contains(pkg)?"Adicionado à dock":"Removido da dock",Toast.LENGTH_SHORT).show();
    }
    private View watchCard(Program p,int generation) {
        FrameLayout card=new FrameLayout(this);card.setBackground(panel(0xff25394b,12,0));card.setClipToOutline(true);
        ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(cover,new FrameLayout.LayoutParams(-1,-1));loadArt(p.art,cover,generation);
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x22000000,0x08000000,0xf5000000}));card.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        TextView source=text(p.source,11,Color.WHITE);source.setPadding(7,3,7,3);source.setBackground(panel(0xb0181d28,6,0));place(card,source,8,8,p.source.equals("YouTube")?67:54,23);
        TextView title=text(p.title,14,Color.WHITE);title.setTypeface(null,Typeface.BOLD);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);place(card,title,10,92,198,21);
        String sub="Continuar no "+p.source;
        if(p.duration>0 && p.position>0)sub="Faltam "+Math.max(1,(p.duration-p.position)/60000)+" min";
        TextView info=text(sub,11,0xffd6dbe4);place(card,info,10,114,198,17);
        if(p.duration>0 && p.position>0) {ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);bar.setProgress((int)Math.min(1000,Math.max(0,(double)p.position/p.duration*1000)));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(p.source.equals("Nuvio")?0xffbd87ff:0xffff414c));place(card,bar,10,132,198,4);}
        card.setContentDescription(p.title+", "+p.source);focus(card,p.id,12,false);card.setOnClickListener(v->launchProgram(p));return card;
    }
    private View fallbackCard(App a,String source) {
        FrameLayout card=new FrameLayout(this);card.setBackground(panel(appColor(a.pkg),12,0x55788899));
        ImageView icon=new ImageView(this);icon.setImageDrawable(a.icon);place(card,icon,12,12,38,38);
        TextView title=text("Abrir "+source,18,Color.WHITE);title.setTypeface(null,Typeface.BOLD);place(card,title,12,64,196,25);
        TextView info=text("Sem cartões de continuação\ndisponíveis no Android TV",11,0xffe1dcea);place(card,info,12,96,196,35);
        focus(card,"fallback:"+a.pkg,12,false);card.setOnClickListener(v->launchApp(a.pkg));return card;
    }
    private void loadArt(String uri,ImageView target,int generation) {
        if(uri==null||uri.isEmpty())return;target.setTag(uri);
        Bitmap cached=images.get(uri);if(cached!=null){target.setImageBitmap(cached);return;}
        if(io.isShutdown())return;
        io.execute(()-> {
            Bitmap bmp=null;
            try {
                byte[] bytes=readBytes(uri,8*1024*1024);
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=1;
                while(bounds.outWidth/opt.inSampleSize>640 || bounds.outHeight/opt.inSampleSize>360)opt.inSampleSize*=2;
                opt.inPreferredConfig=Bitmap.Config.RGB_565;bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
            } catch(Exception ignored) {}
            if(bmp!=null) {Bitmap finalBmp=bmp;images.put(uri,bmp);handler.post(()->{if(alive()&&generation==uiGeneration&&uri.equals(target.getTag()))target.setImageBitmap(finalBmp);});}
        });
    }
    private byte[] readBytes(String address,int limit) throws IOException {
        Uri uri=Uri.parse(address);InputStream stream;HttpURLConnection connection=null;
        if("https".equals(uri.getScheme())) {
            connection=(HttpURLConnection)new URL(address).openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(true);
            if(connection.getResponseCode()!=200){connection.disconnect();throw new IOException("HTTP");}stream=connection.getInputStream();
        } else if("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme()))stream=getContentResolver().openInputStream(uri);
        else throw new IOException("Esquema não permitido");
        if(stream==null)throw new IOException("Sem dados");
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1) {total+=n;if(total>limit)throw new IOException("Imagem/resposta muito grande");out.write(buffer,0,n);}return out.toByteArray();
        } finally {if(connection!=null)connection.disconnect();}
    }
    private void launchApp(String pkg) {
        Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage(pkg);if(intent==null)intent=getPackageManager().getLaunchIntentForPackage(pkg);
        try {if(intent==null)throw new ActivityNotFoundException();startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(RuntimeException e){Toast.makeText(this,"Não foi possível abrir o app",Toast.LENGTH_SHORT).show();}
    }
    private void launchProgram(Program p) {
        if(!p.intent.isEmpty())try {
            Intent intent=Intent.parseUri(p.intent,Intent.URI_INTENT_SCHEME);
            intent.setSelector(null);intent.setComponent(null);intent.setPackage(p.pkg);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);intent.addCategory(Intent.CATEGORY_BROWSABLE);startActivity(intent);return;
        }catch(Exception ignored) {}
        launchApp(p.pkg);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void settingsDialog() {
        String[] choices={"Cidade da previsão do tempo","Organizar aplicativos da dock","Permitir / atualizar Continuar assistindo","Diagnóstico do Nuvio e YouTube","Configurações do Android","Sobre e fontes"};
        new AlertDialog.Builder(this).setTitle("Ajustes do launcher").setItems(choices,(d,which)-> {
            switch(which) {
                case 0:cityDialog();break;
                case 1:manageDock();break;
                case 2:if(checkSelfPermission(TV_PERMISSION)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{TV_PERMISSION},10);else loadData();break;
                case 3:new AlertDialog.Builder(this).setTitle("Continuar assistindo").setMessage(providerStatus+"\n\nA fileira lê apenas o Watch Next publicado pelo Nuvio e YouTube. Nem todas as versões desses apps expõem conteúdos. Permitir listagens de TV não cria um histórico quando o app não o fornece.\n\nNa ausência de cartões, aparecem atalhos para os apps instalados.").setPositiveButton("OK",null).show();break;
                case 4:try {startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(RuntimeException e){Toast.makeText(this,"Configurações indisponíveis",Toast.LENGTH_SHORT).show();}break;
                case 5:new AlertDialog.Builder(this).setTitle("MiStick Launcher 5.0").setMessage("Interface nativa inspirada no tvOS.\nFundo estático; transparência sem blur em tempo real.\n\nPrevisão: Open-Meteo (open-meteo.com). Localidades: GeoNames via Open-Meteo. A cidade pesquisada e suas coordenadas são enviadas ao serviço meteorológico. Nenhuma conta ou chave de API é necessária para uso pessoal.\n\nO launcher não acessa sua conta ou histórico privado do YouTube/Nuvio.").setPositiveButton("OK",null).show();break;
                default:break;
            }
        }).setNegativeButton("Fechar",null).show();
    }
    private void manageDock() {
        CharSequence[] labels=new CharSequence[apps.size()+1];labels[0]="Reordenar fixados";
        for(int i=0;i<apps.size();i++)labels[i+1]=(pinned.contains(apps.get(i).pkg)?"✓  ":"＋  ")+apps.get(i).name;
        new AlertDialog.Builder(this).setTitle("Aplicativos da dock").setItems(labels,(d,i)-> {
            if(i==0)reorderDock();else {togglePin(apps.get(i-1).pkg);manageDock();}
        }).setNegativeButton("Concluído",null).show();
    }
    private void reorderDock() {
        ArrayList<String> installed=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(String pkg:pinned){App a=findApp(pkg);if(a!=null){installed.add(pkg);labels.add(a.name);}}
        if(labels.isEmpty()){Toast.makeText(this,"A dock está vazia",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle("Escolha um app para mover").setItems(labels.toArray(new String[0]),(dialog,index)-> {
            String pkg=installed.get(index);
            new AlertDialog.Builder(this).setTitle(labels.get(index)).setItems(new String[]{"Mover à esquerda","Mover à direita","Remover da dock"},(d,choice)-> {
                int at=pinned.indexOf(pkg);
                if(choice==0&&at>0)Collections.swap(pinned,at,at-1);
                if(choice==1&&at>=0&&at<pinned.size()-1)Collections.swap(pinned,at,at+1);
                if(choice==2)pinned.remove(pkg);
                savePinned();render();updateWeather(false);reorderDock();
            }).setNegativeButton("Voltar",null).show();
        }).setNegativeButton("Concluído",null).show();
    }
    private JSONObject json(String url)throws IOException,JSONException {return new JSONObject(new String(readBytes(url,1024*1024),java.nio.charset.StandardCharsets.UTF_8));}
    private void cityDialog() {
        EditText field=new EditText(this);field.setSingleLine(true);field.setTextSize(18);field.setHint("Ex.: Campinas, São Paulo");field.setPadding(dp(18),dp(12),dp(18),dp(12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Cidade da previsão").setMessage("Digite a cidade e escolha o resultado. A busca usa Open-Meteo/GeoNames; não solicita sua localização GPS.").setView(field).setPositiveButton("Buscar",null).setNegativeButton("Cancelar",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String query=field.getText().toString().trim();if(query.length()<2){field.setError("Digite pelo menos 2 letras");return;}
            int ticket=++citySearchGeneration;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            io.execute(()-> {
                JSONArray results=null;try{results=json("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(query,"UTF-8")+"&count=5&language=pt&format=json").optJSONArray("results");}catch(Exception ignored){}
                JSONArray found=results;
                handler.post(()-> {
                    if(!alive()||ticket!=citySearchGeneration||!dialog.isShowing())return;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if(found==null||found.length()==0){field.setError("Sem resultados ou sem conexão. Tente cidade, estado.");return;}
                    String[] labels=new String[found.length()];
                    for(int i=0;i<found.length();i++){JSONObject c=found.optJSONObject(i);labels[i]=c.optString("name")+" · "+c.optString("admin1")+" · "+c.optString("country");}
                    dialog.dismiss();new AlertDialog.Builder(this).setTitle("Selecione sua cidade").setItems(labels,(selectionDialog,i)-> {
                        JSONObject c=found.optJSONObject(i);
                        weatherGeneration++;prefs.edit().putString("weather_lat",c.optString("latitude")).putString("weather_lon",c.optString("longitude")).putString("weather_city",c.optString("name")).remove("weather_cached").remove("weather_updated").apply();
                        updateWeather(true);
                    }).setNegativeButton("Cancelar",null).show();
                });
            });
        }));dialog.show();field.requestFocus();
    }
    private void updateWeather(boolean force) {
        if(weather==null||io.isShutdown())return;
        String lat=prefs.getString("weather_lat","");String lon=prefs.getString("weather_lon","");String city=prefs.getString("weather_city","");
        if(lat.isEmpty()||lon.isEmpty()){weather.setText("☁  Configurar previsão\nEscolha sua cidade");return;}
        long updated=prefs.getLong("weather_updated",0);String cached=prefs.getString("weather_cached","");
        if(!cached.isEmpty())weather.setText(cached+(System.currentTimeMillis()-updated>60*60*1000?" · desatualizado":""));
        if(!force && !cached.isEmpty() && System.currentTimeMillis()-updated<30*60*1000)return;
        int ticket=++weatherGeneration;
        if(cached.isEmpty())weather.setText("☁  "+city+"\nAtualizando previsão…");
        io.execute(()-> {
            String result=null;
            try {
                JSONObject data=json("https://api.open-meteo.com/v1/forecast?latitude="+URLEncoder.encode(lat,"UTF-8")+"&longitude="+URLEncoder.encode(lon,"UTF-8")+"&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
                JSONObject now=data.getJSONObject("current"),daily=data.getJSONObject("daily");int code=now.getInt("weather_code");
                String icon=code<=1?(now.optInt("is_day",1)==1?"☀":"☾"):(code<=3||code==45||code==48)?"☁":code>=95?"ϟ":((code>=71&&code<=77)||(code>=85&&code<=86))?"❄":"☂";
                result=icon+"  "+Math.round(now.getDouble("temperature_2m"))+"°C  "+weatherLabel(code)+"\nMáx. "+Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))+"° · Mín. "+Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))+"° · "+city;
            }catch(Exception ignored){}
            String value=result;
            handler.post(()-> {
                if(!alive()||ticket!=weatherGeneration)return;
                if(value!=null){prefs.edit().putString("weather_cached",value).putLong("weather_updated",System.currentTimeMillis()).apply();weather.setText(value);}
                else weather.setText(cached.isEmpty()?"☁  "+city+"\nPrevisão indisponível · tente mais tarde":cached+" · sem atualização");
            });
        });
    }
    static String weatherLabel(int code) {
        if(code==0)return "Céu limpo";if(code==1)return "Predominantemente limpo";if(code==2)return "Parcialmente nublado";if(code==3)return "Nublado";if(code==45||code==48)return "Nevoeiro";if(code>=51&&code<=57)return "Garoa";if(code>=61&&code<=67)return "Chuva";if(code>=71&&code<=77)return "Neve";if(code>=80&&code<=82)return "Pancadas de chuva";if(code>=85&&code<=86)return "Pancadas de neve";if(code>=95)return "Trovoadas";return "Condição indisponível";
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==10)loadData();}
    @Override public void onBackPressed() {if(mainScroll!=null){mainScroll.smoothScrollTo(0,0);if(firstTile!=null)firstTile.requestFocus();else if(gear!=null)gear.requestFocus();}}
}
