package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.database.DataSnapshot;
import com.tridev.familyhub.R;
import com.tridev.familyhub.data.model.FamilyLiveCloudMember;

import java.util.Date;
import java.util.Locale;

final class ChatAttachmentCards {
    private final Context context;
    private final ChatAttachments attachments;

    ChatAttachmentCards(Context context, ChatAttachments attachments) {
        this.context = context;
        this.attachments = attachments;
    }

    View create(FamilyChatStore.ThreadState thread, FamilyChatStore.Message message, int mediaWidth) {
        String kind = message.kind();
        if (message.deleted() || "text".equals(kind)) return null;
        Runnable open = () -> attachments.open(thread, message);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        switch (kind) {
            case "photo":
                FrameLayout photo = featureArea(mediaWidth);
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setContentDescription("Open photo full screen");
                photo.addView(image, new FrameLayout.LayoutParams(-1, -1));
                TextView hint = label("Loading photo…", false);
                hint.setGravity(Gravity.CENTER);
                photo.addView(hint, new FrameLayout.LayoutParams(-1, -1));
                photo.setOnClickListener(v -> open.run());
                card.addView(photo);
                whenVisible(image, () -> attachments.loadPhoto(thread, message, bitmap -> {
                    if (!image.isAttachedToWindow()) return;
                    if (bitmap == null) {
                        hint.setText("Photo unavailable · Tap to retry");
                        return;
                    }
                    image.setImageBitmap(bitmap);
                    hint.setVisibility(View.GONE);
                    photo.setOnClickListener(v -> attachments.showFullPhoto(thread, bitmap));
                }));
                break;
            case "location":
            case "live":
                String heading = "live".equals(kind) ? "Family Live location" : "Shared location";
                card.addView(label(heading, true));
                LocationPreview preview = new LocationPreview(context, open);
                int mapHeight = Math.round(mediaWidth * 0.82f);
                card.addView(preview, new LinearLayout.LayoutParams(-1, mapHeight + dp(36)));
                if ("live".equals(kind)) {
                    whenVisible(preview, () -> attachments.observeLive(message.liveMember(), preview.liveUpdate));
                } else {
                    Double lat = message.snapshot.child("lat").getValue(Double.class);
                    Double lng = message.snapshot.child("lng").getValue(Double.class);
                    if (lat != null && lng != null) whenVisible(preview,
                            () -> preview.showPoint(lat, lng, String.format(Locale.ROOT, "%.5f, %.5f", lat, lng)));
                }
                card.addView(action("live".equals(kind) ? "Family Map" : "Open map", open));
                if ("location".equals(kind)) {
                    card.addView(action("Family Map", () -> attachments.openFamilyMap(thread, message)));
                }
                break;
            case "poll":
                Long deadline = message.pollDeadline();
                card.addView(label(deadline == null ? "Poll" : message.pollClosed()
                        ? "Voting closed" : "Closes · " + attachments.pollDate(deadline), false));
                card.addView(label(message.body, true));
                String selected = attachments.selectedVote(message);
                for (DataSnapshot option : message.snapshot.child("options").getChildren()) {
                    String value = option.getValue(String.class);
                    boolean voted = option.getKey() != null && option.getKey().equals(selected);
                    MaterialCardView row = surface();
                    TextView optionText = label((voted ? "✓  " : "○  ") + value, voted);
                    optionText.setPadding(dp(8), dp(4), dp(8), dp(4));
                    row.addView(optionText);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                    params.topMargin = dp(4);
                    card.addView(row, params);
                }
                LinearLayout actions = new LinearLayout(context);
                MaterialButton vote = action("Vote", open);
                vote.setVisibility(message.pollClosed() ? View.GONE : View.VISIBLE);
                if (selected != null) vote.setContentDescription("Change vote");
                MaterialButton results = action("Results", () -> attachments.showPollResults(message));
                for (MaterialButton button : new MaterialButton[]{vote, results}) {
                    button.setMinWidth(0);
                    button.setMinimumWidth(0);
                    button.setPaddingRelative(dp(3), button.getPaddingTop(), dp(3), button.getPaddingBottom());
                    actions.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
                }
                card.addView(actions);
                break;
            case "announcement":
                card.addView(label("Announcement", false));
                card.addView(label(message.body, true));
                if (attachments.announcementAuthor(message)) {
                    card.addView(action("Responses · " + message.snapshot.child("acks").getChildrenCount(),
                            () -> attachments.announcementResponses(thread, message)));
                } else {
                    boolean acknowledged = attachments.acknowledged(message);
                    MaterialButton confirm = action(acknowledged ? "Acknowledged" : "Got it",
                            () -> attachments.acknowledge(thread, message));
                    confirm.setEnabled(!acknowledged);
                    card.addView(confirm);
                }
                break;
            case "checklist":
                card.addView(label("Shared checklist", false));
                card.addView(label(message.body, true));
                for (DataSnapshot entry : message.snapshot.child("entries").getChildren()) {
                    String id = entry.getKey();
                    if (id == null) continue;
                    boolean checked = message.snapshot.child("checks").child(id).exists();
                    MaterialButton item = action((checked ? "✓  " : "○  ") + entry.getValue(String.class),
                            () -> attachments.toggleChecklist(thread, message, id));
                    item.setMaxLines(4);
                    card.addView(item);
                }
                break;
            case "voice":
                return attachments.voiceMessage(thread, message);
            case "file":
                details(card, "File", message.body, message.snapshot.child("mime").getValue(String.class),
                        "Open file", open);
                break;
            case "contact":
                details(card, "Contact", message.body, message.snapshot.child("phone").getValue(String.class),
                        "View contact", open);
                break;
            case "event":
                Long start = message.snapshot.child("startsAt").getValue(Long.class);
                Date date = start == null ? null : new Date(start);
                details(card, "Event", message.body, date == null ? null
                                : DateFormat.getMediumDateFormat(context).format(date) + " · "
                                + DateFormat.getTimeFormat(context).format(date),
                        "Add to calendar", open);
                break;
            default:
                return null;
        }
        return card;
    }

    private void details(LinearLayout card, String type, String title, String subtitle,
                         String button, Runnable open) {
        MaterialCardView panel = surface();
        LinearLayout contents = new LinearLayout(context);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(dp(12), dp(6), dp(12), dp(6));
        contents.addView(label(type, false));
        contents.addView(label(title, true));
        if (subtitle != null) contents.addView(label(subtitle, false));
        panel.addView(contents);
        card.addView(panel);
        card.addView(action(button, open));
    }

    private FrameLayout featureArea(int height) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(color(R.color.fh_primary_container));
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        return frame;
    }

    private MaterialCardView surface() {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(color(R.color.fh_surface));
        card.setStrokeColor(color(R.color.fh_outline));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(12));
        card.setCardElevation(0);
        return card;
    }

    private TextView label(String value, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(bold ? 16 : 13);
        text.setTextColor(color(bold ? R.color.fh_on_surface : R.color.fh_text_secondary));
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setPadding(0, dp(4), 0, dp(4));
        return text;
    }

    private MaterialButton action(String label, Runnable action) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(color(R.color.fh_primary));
        button.setStrokeColor(ColorStateList.valueOf(color(R.color.fh_outline)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private int color(int resource) {
        return androidx.core.content.ContextCompat.getColor(context, resource);
    }

    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }

    private static void whenVisible(View view, Runnable action) {
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            ViewTreeObserver.OnPreDrawListener listener;
            @Override public void onViewAttachedToWindow(View attached) {
                listener = () -> {
                    if (view.isShown() && view.getGlobalVisibleRect(new Rect())) {
                        remove();
                        view.removeOnAttachStateChangeListener(this);
                        action.run();
                    }
                    return true;
                };
                view.getViewTreeObserver().addOnPreDrawListener(listener);
            }
            @Override public void onViewDetachedFromWindow(View detached) { remove(); }
            private void remove() {
                if (listener != null && view.getViewTreeObserver().isAlive()) {
                    view.getViewTreeObserver().removeOnPreDrawListener(listener);
                }
                listener = null;
            }
        });
    }

    private final class LocationPreview extends FrameLayout {
        private final TextView caption;
        private final Runnable open;
        private MapView map;
        private com.google.android.gms.maps.GoogleMap readyMap;
        private LatLng point;
        private final java.util.function.Consumer<FamilyLiveCloudMember> liveUpdate = this::showMember;

        LocationPreview(Context context, Runnable open) {
            super(context);
            this.open = open;
            setBackgroundColor(color(R.color.fh_primary_container));
            caption = label("Location preview unavailable", true);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine();
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            caption.setHeight(dp(36));
            caption.setBackgroundColor(color(R.color.fh_primary_container));
            addView(caption, new LayoutParams(-1, -2, Gravity.BOTTOM));
            setOnClickListener(v -> open.run());
        }

        void showMember(FamilyLiveCloudMember member) {
            if (!isAttachedToWindow()) return;
            if (member != null && member.hasLocation && member.sharingEnabled) {
                showPoint(member.latitude, member.longitude,
                        member.placeLabel.isEmpty() ? "Family Live" : member.placeLabel);
            } else {
                destroyMap();
                caption.setText("Live location unavailable");
            }
        }

        void showPoint(double latitude, double longitude, String label) {
            if (!isAttachedToWindow() || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;
            caption.setText(label);
            LatLng next = new LatLng(latitude, longitude);
            boolean changed = !next.equals(point);
            point = next;
            if (map == null) {
                map = new MapView(getContext(), new GoogleMapOptions().liteMode(true));
                LayoutParams mapParams = new LayoutParams(-1, -1);
                mapParams.bottomMargin = dp(36);
                addView(map, 0, mapParams);
                MapView current = map;
                map.onCreate(null);
                map.getMapAsync(googleMap -> {
                    if (map != current || !isAttachedToWindow()) return;
                    readyMap = googleMap;
                    googleMap.getUiSettings().setMapToolbarEnabled(false);
                    googleMap.setOnMapClickListener(location -> open.run());
                    googleMap.setOnMarkerClickListener(marker -> { open.run(); return true; });
                    updateMap();
                });
            } else if (changed) updateMap();
        }

        private void updateMap() {
            if (readyMap == null || point == null) return;
            readyMap.clear();
            readyMap.addMarker(new MarkerOptions().position(point));
            readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 15f));
        }

        private void destroyMap() {
            if (map == null) return;
            MapView old = map;
            map = null;
            readyMap = null;
            removeView(old);
            old.onDestroy();
        }

        @Override protected void onDetachedFromWindow() {
            destroyMap();
            super.onDetachedFromWindow();
        }
    }
}
