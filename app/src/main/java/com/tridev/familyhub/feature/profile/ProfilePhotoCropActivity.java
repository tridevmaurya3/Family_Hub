package com.tridev.familyhub.feature.profile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local square avatar editor. Nothing is uploaded until the caller accepts its result. */
public final class ProfilePhotoCropActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CropView crop;
    private MaterialButton save;
    private Uri source;
    private int turns;
    private boolean busy;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        source=getIntent().getData();
        if(source==null){finish();return;}
        turns=state==null?0:state.getInt("turns");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(16));
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,com.tridev.familyhub.R.color.fh_background));
        TextView title=new TextView(this);title.setText("Crop profile photo");title.setTextSize(20);root.addView(title);
        TextView hint=new TextView(this);hint.setText("Drag to position • Pinch to zoom • Rotate to adjust");root.addView(hint);
        crop=new CropView();root.addView(crop,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton cancel=button("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
        MaterialButton rotate=button("Rotate ↻");rotate.setOnClickListener(v->{if(!busy&&crop.photo!=null){turns=(turns+1)%4;crop.rotate();}});actions.addView(rotate,new LinearLayout.LayoutParams(0,-2,1));
        save=button("Save");save.setEnabled(false);save.setOnClickListener(v->saveCrop());actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets safe=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(16)+safe.left,dp(16)+safe.top,dp(16)+safe.right,dp(16)+safe.bottom);return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(root);
        worker.execute(()->{
            Bitmap image=null;
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                try(InputStream in=getContentResolver().openInputStream(source)){BitmapFactory.decodeStream(in,null,bounds);}
                if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException();
                BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=1;
                while(Math.max(bounds.outWidth,bounds.outHeight)/opts.inSampleSize>1600)opts.inSampleSize*=2;
                try(InputStream in=getContentResolver().openInputStream(source)){image=BitmapFactory.decodeStream(in,null,opts);}
                if(image!=null){
                    int orientation=1;try(InputStream in=getContentResolver().openInputStream(source)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
                    Matrix m=new Matrix();
                    switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:break;}
                    image=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),m,true);
                }
            }catch(Exception|OutOfMemoryError ignored){}
            final Bitmap decoded=image;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(decoded==null){Toast.makeText(this,"Photo could not be opened",Toast.LENGTH_LONG).show();finish();return;}crop.photo=decoded;for(int i=0;i<turns;i++)crop.rotate();crop.reset();save.setEnabled(true);});
        });
    }
    private void saveCrop(){
        if(busy||crop.photo==null)return;
        busy=true;save.setEnabled(false);
        final Bitmap result=crop.render();
        worker.execute(()->{Uri output=null;try{
            File dir=new File(getCacheDir(),"profile_crops");if(!dir.exists()&&!dir.mkdirs())throw new IOException();
            File file=File.createTempFile("avatar-",".jpg",dir);
            try(OutputStream out=new FileOutputStream(file)){if(!result.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException();}
            output=FileProvider.getUriForFile(this,getPackageName()+".backupfiles",file);
        }catch(Exception ignored){}finally{result.recycle();}
        final Uri uri=output;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;if(uri==null){save.setEnabled(true);Toast.makeText(this,"Photo could not be saved",Toast.LENGTH_LONG).show();return;}setResult(Activity.RESULT_OK,new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));finish();});});
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("turns",turns);}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    private MaterialButton button(String text){MaterialButton b=new MaterialButton(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private final class CropView extends View {
        Bitmap photo;float scale,minScale,x,y,lastX,lastY;final RectF frame=new RectF();final Paint paint=new Paint(3);
        final ScaleGestureDetector pinch=new ScaleGestureDetector(ProfilePhotoCropActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale;scale=Math.max(minScale,Math.min(minScale*8,scale*d.getScaleFactor()));float ratio=scale/old;x=d.getFocusX()+(x-d.getFocusX())*ratio;y=d.getFocusY()+(y-d.getFocusY())*ratio;clamp();invalidate();return true;}});
        CropView(){super(ProfilePhotoCropActivity.this);setContentDescription("Square crop area. Drag photo and pinch to zoom.");}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){float side=Math.max(1,Math.min(w,h)-dp(24));frame.set((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);reset();}
        void reset(){if(photo==null||frame.width()==0)return;minScale=Math.max(frame.width()/photo.getWidth(),frame.height()/photo.getHeight());scale=minScale;x=frame.centerX()-photo.getWidth()*scale/2;y=frame.centerY()-photo.getHeight()*scale/2;invalidate();}
        void rotate(){if(photo==null)return;Matrix m=new Matrix();m.setRotate(90);try{Bitmap old=photo;photo=Bitmap.createBitmap(old,0,0,old.getWidth(),old.getHeight(),m,true);if(old!=photo)old.recycle();reset();}catch(OutOfMemoryError error){Toast.makeText(ProfilePhotoCropActivity.this,"Photo could not be rotated",Toast.LENGTH_SHORT).show();}}
        void clamp(){if(photo==null)return;x=Math.min(frame.left,Math.max(frame.right-photo.getWidth()*scale,x));y=Math.min(frame.top,Math.max(frame.bottom-photo.getHeight()*scale,y));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(Color.rgb(30,30,35));if(photo!=null)c.drawBitmap(photo,null,new RectF(x,y,x+photo.getWidth()*scale,y+photo.getHeight()*scale),paint);paint.setColor(0x99000000);c.drawRect(0,0,getWidth(),frame.top,paint);c.drawRect(0,frame.bottom,getWidth(),getHeight(),paint);c.drawRect(0,frame.top,frame.left,frame.bottom,paint);c.drawRect(frame.right,frame.top,getWidth(),frame.bottom,paint);paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));c.drawRect(frame,paint);paint.setStyle(Paint.Style.FILL);}
        @Override public boolean onTouchEvent(MotionEvent e){if(photo==null||busy)return true;pinch.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(!pinch.isInProgress()&&e.getPointerCount()==1){x+=e.getX()-lastX;y+=e.getY()-lastY;clamp();invalidate();}lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP){int remaining=e.getActionIndex()==0?1:0;lastX=e.getX(remaining);lastY=e.getY(remaining);}return true;}
        Bitmap render(){Bitmap result=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(result);float ratio=640/frame.width();c.translate(-frame.left*ratio,-frame.top*ratio);c.scale(ratio,ratio);c.drawBitmap(photo,null,new RectF(x,y,x+photo.getWidth()*scale,y+photo.getHeight()*scale),paint);return result;}
    }
}
