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

/** Local photo editor for profile avatars and chat attachments. */
public final class ProfilePhotoCropActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_PHOTO = "chat_photo";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CropView crop;
    private MaterialButton save;
    private TextView hint;
    private Uri source;
    private int turns;
    private boolean chatPhoto;
    private boolean mirrored;
    private int cropFormat;
    private static final int FREE_CROP = 4;
    private static final String[] CROP_FORMATS = {"Original", "Square", "Landscape 4:3", "Portrait 3:4", "Free crop"};
    private boolean busy;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        source=getIntent().getData();
        if(source==null){finish();return;}
        chatPhoto = getIntent().getBooleanExtra(EXTRA_CHAT_PHOTO, false);
        turns = state == null ? 0 : state.getInt("turns");
        mirrored = state != null && state.getBoolean("mirrored");
        cropFormat = state == null ? 0 : state.getInt("cropFormat");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(16));
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,com.tridev.familyhub.R.color.fh_background));
        TextView title=new TextView(this);title.setText(chatPhoto ? "Edit chat photo" : "Crop profile photo");title.setTextSize(20);root.addView(title);
        hint = new TextView(this);
        updateCropHint();
        root.addView(hint);
        crop=new CropView();root.addView(crop,new LinearLayout.LayoutParams(-1,0,1));
        if (chatPhoto) addChatEditingActions(root);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton cancel=button("Cancel");cancel.setOnClickListener(v->finish());actions.addView(cancel,new LinearLayout.LayoutParams(0,-2,1));
        MaterialButton rotate=button("Rotate ↻");rotate.setOnClickListener(v->{if (!busy && crop.photo != null && crop.rotate()) turns = (turns + (mirrored ? 3 : 1)) % 4;});actions.addView(rotate,new LinearLayout.LayoutParams(0,-2,1));
        save=button(chatPhoto ? "Use photo" : "Save");save.setEnabled(false);save.setOnClickListener(v->saveCrop());actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
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
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(decoded==null){Toast.makeText(this,"Photo could not be opened",Toast.LENGTH_LONG).show();finish();return;}crop.photo=decoded;for(int i=0;i<turns;i++)crop.rotate();if(mirrored)crop.flip();crop.updateFrame();save.setEnabled(true);});
        });
    }
    private void addChatEditingActions(LinearLayout root) {
        LinearLayout editing = new LinearLayout(this);
        MaterialButton format = button("Crop: " + CROP_FORMATS[cropFormat]);
        format.setOnClickListener(v -> {
            if (busy || crop.photo == null) return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Crop shape")
                    .setSingleChoiceItems(CROP_FORMATS, cropFormat, (dialog, selected) -> {
                        cropFormat = selected;
                        format.setText("Crop: " + CROP_FORMATS[selected]);
                        crop.updateFrame();
                        updateCropHint();
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        editing.addView(format, new LinearLayout.LayoutParams(0, -2, 2));
        MaterialButton flip = button("Flip");
        flip.setOnClickListener(v -> {
            if (!busy && crop.photo != null && crop.flip()) mirrored = !mirrored;
        });
        editing.addView(flip, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton reset = button("Recenter");
        reset.setOnClickListener(v -> {
            if (!busy) crop.updateFrame();
        });
        editing.addView(reset, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(editing);
    }

    private void updateCropHint() {
        if (!chatPhoto) {
            hint.setText("Drag to position • Pinch to zoom • Rotate to adjust");
        } else if (cropFormat == FREE_CROP) {
            hint.setText("Drag any edge or corner to resize. Drag inside to pan the photo.");
        } else {
            hint.setText("Choose a crop shape, then drag or pinch to frame your photo.");
        }
    }

    private void saveCrop(){
        if (busy || crop.photo == null || crop.frame.isEmpty()) return;
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
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("turns", turns);
        state.putBoolean("mirrored", mirrored);
        state.putInt("cropFormat", cropFormat);
    }
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    private MaterialButton button(String text){MaterialButton b=new MaterialButton(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private final class CropView extends View {
        private static final int LEFT = 1, TOP = 2, RIGHT = 4, BOTTOM = 8;
        private int resizeEdges;
        Bitmap photo;float scale,minScale,x,y,lastX,lastY;final RectF frame=new RectF();final Paint paint=new Paint(3);
        final ScaleGestureDetector pinch=new ScaleGestureDetector(ProfilePhotoCropActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){float old=scale;scale=Math.max(minScale,Math.min(minScale*8,scale*d.getScaleFactor()));float ratio=scale/old;x=d.getFocusX()+(x-d.getFocusX())*ratio;y=d.getFocusY()+(y-d.getFocusY())*ratio;clamp();invalidate();return true;}});
        CropView(){super(ProfilePhotoCropActivity.this);setContentDescription("Crop area. Drag photo and pinch to zoom.");}
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            updateFrame();
        }

        void updateFrame() {
            float aspect = 1f;
            if (chatPhoto) {
                if ((cropFormat == 0 || cropFormat == FREE_CROP) && photo != null) aspect = (float) photo.getWidth() / photo.getHeight();
                else if (cropFormat == 2) aspect = 4f / 3f;
                else if (cropFormat == 3) aspect = 3f / 4f;
            }
            float width = Math.max(1, getWidth() - dp(24));
            float height = Math.max(1, getHeight() - dp(24));
            float frameWidth = Math.min(width, height * aspect);
            float frameHeight = frameWidth / aspect;
            float left = (getWidth() - frameWidth) / 2f;
            float top = (getHeight() - frameHeight) / 2f;
            frame.set(left, top, left + frameWidth, top + frameHeight);
            reset();
        }

        void reset(){if(photo==null||frame.width()==0)return;minScale=Math.max(frame.width()/photo.getWidth(),frame.height()/photo.getHeight());scale=minScale;x=frame.centerX()-photo.getWidth()*scale/2;y=frame.centerY()-photo.getHeight()*scale/2;invalidate();}

        boolean rotate() {
            Matrix matrix = new Matrix();
            matrix.setRotate(90);
            return transform(matrix);
        }

        boolean flip() {
            Matrix matrix = new Matrix();
            matrix.setScale(-1, 1);
            return transform(matrix);
        }

        private boolean transform(Matrix matrix) {
            Bitmap old = photo;
            try {
                photo = Bitmap.createBitmap(old, 0, 0, old.getWidth(), old.getHeight(), matrix, true);
            } catch (OutOfMemoryError error) {
                Toast.makeText(ProfilePhotoCropActivity.this, "Photo could not be edited", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (photo != old) old.recycle();
            updateFrame();
            return true;
        }

        void clamp(){if(photo==null)return;x=Math.min(frame.left,Math.max(frame.right-photo.getWidth()*scale,x));y=Math.min(frame.top,Math.max(frame.bottom-photo.getHeight()*scale,y));}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(30, 30, 35));
            if (photo != null) {
                canvas.drawBitmap(photo, null, new RectF(x, y, x + photo.getWidth() * scale,
                        y + photo.getHeight() * scale), paint);
            }
            paint.setColor(0x99000000);
            canvas.drawRect(0, 0, getWidth(), frame.top, paint);
            canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, paint);
            canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, paint);
            if (chatPhoto && photo != null) {
                paint.setColor(0x80ffffff);
                paint.setStrokeWidth(dp(1));
                for (int line = 1; line <= 2; line++) {
                    float gridX = frame.left + frame.width() * line / 3;
                    float gridY = frame.top + frame.height() * line / 3;
                    canvas.drawLine(gridX, frame.top, gridX, frame.bottom, paint);
                    canvas.drawLine(frame.left, gridY, frame.right, gridY, paint);
                }
            }
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            canvas.drawRect(frame, paint);
            paint.setStyle(Paint.Style.FILL);
            if (chatPhoto && cropFormat == FREE_CROP && photo != null) {
                paint.setColor(0xff60ddbf);
                float radius = dp(5);
                canvas.drawCircle(frame.left, frame.top, radius, paint);
                canvas.drawCircle(frame.right, frame.top, radius, paint);
                canvas.drawCircle(frame.left, frame.bottom, radius, paint);
                canvas.drawCircle(frame.right, frame.bottom, radius, paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (photo == null || busy) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
                resizeEdges = chatPhoto && cropFormat == FREE_CROP ? edgeAt(lastX, lastY) : 0;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) resizeEdges = 0;
            pinch.onTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !pinch.isInProgress()) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (resizeEdges != 0) resize(dx, dy);
                else {
                    x += dx;
                    y += dy;
                    clamp();
                }
                invalidate();
            }
            int pointer = action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == 0 ? 1 : 0;
            lastX = event.getX(pointer);
            lastY = event.getY(pointer);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) resizeEdges = 0;
            return true;
        }

        private int edgeAt(float touchX, float touchY) {
            float hit = dp(20);
            if (touchX < frame.left - hit || touchX > frame.right + hit
                    || touchY < frame.top - hit || touchY > frame.bottom + hit) return 0;
            int edges = 0;
            if (Math.abs(touchX - frame.left) < hit) edges |= LEFT;
            else if (Math.abs(touchX - frame.right) < hit) edges |= RIGHT;
            if (Math.abs(touchY - frame.top) < hit) edges |= TOP;
            else if (Math.abs(touchY - frame.bottom) < hit) edges |= BOTTOM;
            return edges;
        }

        private void resize(float dx, float dy) {
            float minWidth = Math.min(dp(56), frame.width());
            float minHeight = Math.min(dp(56), frame.height());
            float boundLeft = Math.max(dp(4), x);
            float boundTop = Math.max(dp(4), y);
            float boundRight = Math.min(getWidth() - dp(4), x + photo.getWidth() * scale);
            float boundBottom = Math.min(getHeight() - dp(4), y + photo.getHeight() * scale);
            if ((resizeEdges & LEFT) != 0) frame.left = bounded(frame.left + dx, boundLeft, frame.right - minWidth);
            if ((resizeEdges & RIGHT) != 0) frame.right = bounded(frame.right + dx, frame.left + minWidth, boundRight);
            if ((resizeEdges & TOP) != 0) frame.top = bounded(frame.top + dy, boundTop, frame.bottom - minHeight);
            if ((resizeEdges & BOTTOM) != 0) frame.bottom = bounded(frame.bottom + dy, frame.top + minHeight, boundBottom);
            minScale = Math.max(frame.width() / photo.getWidth(), frame.height() / photo.getHeight());
        }

        private float bounded(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        Bitmap render() {
            int longestEdge = chatPhoto ? 960 : 640;
            float ratio = longestEdge / Math.max(frame.width(), frame.height());
            int width = Math.max(1, Math.round(frame.width() * ratio));
            int height = Math.max(1, Math.round(frame.height() * ratio));
            Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.scale(width / frame.width(), height / frame.height());
            canvas.translate(-frame.left, -frame.top);
            canvas.drawBitmap(photo, null, new RectF(x, y, x + photo.getWidth() * scale,
                    y + photo.getHeight() * scale), paint);
            return result;
        }
    }
}
