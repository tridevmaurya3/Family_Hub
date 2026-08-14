package com.tridev.familyhub.feature.help;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.tridev.familyhub.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bilingual, searchable, step-by-step guide for every major Family Hub feature. */
public class HelpActivity extends AppCompatActivity {

    private static final String PREFS = "family_hub_help";
    private static final String KEY_HINDI = "hindi_selected";

    private final List<Guide> guides = new ArrayList<>();
    private LinearLayout guideContainer;
    private TextView titleView;
    private TextView subtitleView;
    private TextView resultView;
    private EditText searchInput;
    private MaterialButton hindiButton;
    private MaterialButton englishButton;
    private TextView expandedStepView;
    private TextView expandedArrowView;
    private boolean hindi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        hindi = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HINDI, true);
        createGuides();
        setContentView(buildScreen());
        updateLanguage();
    }

    @NonNull
    private View buildScreen() {
        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundResource(R.drawable.bg_page_three_tone);

        LinearLayout root = vertical();
        root.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(root, matchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setColorFilter(color(R.color.fh_primary));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout heading = vertical();
        titleView = text(24, true, R.color.fh_text_primary);
        subtitleView = text(13, false, R.color.fh_text_secondary);
        heading.addView(titleView);
        heading.addView(subtitleView, topMargin(2));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1F);
        headingParams.leftMargin = dp(8);
        header.addView(heading, headingParams);
        root.addView(header);

        MaterialCardView languageCard = card(R.color.fh_primary_container, R.color.fh_primary);
        LinearLayout languageRow = new LinearLayout(this);
        languageRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        languageRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView languageLabel = text(13, true, R.color.fh_text_primary);
        languageLabel.setTag("language_label");
        languageRow.addView(languageLabel, new LinearLayout.LayoutParams(0, -2, 1F));
        hindiButton = languageButton("हिन्दी");
        englishButton = languageButton("English");
        languageRow.addView(hindiButton);
        LinearLayout.LayoutParams englishParams = new LinearLayout.LayoutParams(-2, dp(42));
        englishParams.leftMargin = dp(6);
        languageRow.addView(englishButton, englishParams);
        languageCard.addView(languageRow);
        root.addView(languageCard, topMargin(14));

        hindiButton.setOnClickListener(v -> selectLanguage(true));
        englishButton.setOnClickListener(v -> selectLanguage(false));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(color(R.color.fh_text_primary));
        searchInput.setHintTextColor(color(R.color.fh_text_secondary));
        searchInput.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        searchInput.setCompoundDrawablePadding(dp(10));
        searchInput.setPadding(dp(16), 0, dp(16), 0);
        searchInput.setBackgroundResource(R.drawable.bg_help_search);
        root.addView(searchInput, fixedTop(-1, dp(56), 14));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderGuides(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        resultView = text(12, true, R.color.fh_primary);
        root.addView(resultView, topMargin(12));
        guideContainer = vertical();
        root.addView(guideContainer, topMargin(4));

        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        return scroll;
    }

    private void selectLanguage(boolean useHindi) {
        if (hindi == useHindi) return;
        hindi = useHindi;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HINDI, hindi).apply();
        updateLanguage();
    }

    private void updateLanguage() {
        titleView.setText(hindi ? "सहायता और मार्गदर्शिका" : "Help & Guide");
        subtitleView.setText(hindi
                ? "Family Hub के हर फीचर का आसान तरीका"
                : "Simple steps for every Family Hub feature");
        searchInput.setHint(hindi ? "फीचर या सहायता खोजें" : "Search a feature or help topic");
        TextView label = findTaggedText((View) hindiButton.getParent(), "language_label");
        if (label != null) label.setText(hindi ? "भाषा चुनें" : "Choose language");
        styleLanguageButton(hindiButton, hindi);
        styleLanguageButton(englishButton, !hindi);
        renderGuides(searchInput.getText() == null ? "" : searchInput.getText().toString());
    }

    private void renderGuides(@NonNull String query) {
        expandedStepView = null;
        expandedArrowView = null;
        guideContainer.removeAllViews();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Guide guide : guides) {
            String title = hindi ? guide.hiTitle : guide.enTitle;
            String steps = hindi ? guide.hiSteps : guide.enSteps;
            if (!normalized.isEmpty()
                    && !(title + " " + steps).toLowerCase(Locale.ROOT).contains(normalized)) {
                continue;
            }
            guideContainer.addView(buildGuideCard(title, steps, shown), topMargin(8));
            shown++;
        }
        resultView.setText(hindi
                ? shown + " सहायता विषय"
                : shown + (shown == 1 ? " help topic" : " help topics"));
        if (shown == 0) {
            TextView empty = text(14, false, R.color.fh_text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setText(hindi
                    ? "कोई सहायता विषय नहीं मिला। दूसरे शब्द से खोजें।"
                    : "No help topic found. Try another search.");
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            guideContainer.addView(empty);
        }
    }

    private View buildGuideCard(String title, String steps, int position) {
        int[] fills = {R.color.fh_info_container, R.color.fh_success_container,
                R.color.fh_secondary_container, R.color.fh_warning_container};
        int[] strokes = {R.color.fh_info, R.color.fh_success,
                R.color.fh_secondary, R.color.fh_warning};
        MaterialCardView card = card(fills[position % fills.length], strokes[position % strokes.length]);
        LinearLayout body = vertical();
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = text(13, true, strokes[position % strokes.length]);
        number.setGravity(Gravity.CENTER);
        number.setText(String.valueOf(position + 1));
        heading.addView(number, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView titleView = text(15, true, R.color.fh_text_primary);
        titleView.setText(title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1F);
        titleParams.leftMargin = dp(10);
        heading.addView(titleView, titleParams);
        TextView arrow = text(18, true, R.color.fh_text_secondary);
        arrow.setText("+");
        heading.addView(arrow);
        body.addView(heading);

        TextView stepView = text(13, false, R.color.fh_text_secondary);
        stepView.setText(steps);
        stepView.setLineSpacing(dp(3), 1F);
        stepView.setVisibility(View.GONE);
        body.addView(stepView, topMargin(10));
        View.OnClickListener toggle = v -> {
            boolean open = stepView.getVisibility() == View.VISIBLE;
            if (expandedStepView != null && expandedStepView != stepView) {
                expandedStepView.setVisibility(View.GONE);
                if (expandedArrowView != null) {
                    expandedArrowView.setText("+");
                }
            }
            if (open) {
                stepView.setVisibility(View.GONE);
                arrow.setText("+");
                expandedStepView = null;
                expandedArrowView = null;
            } else {
                stepView.setVisibility(View.VISIBLE);
                arrow.setText("−");
                expandedStepView = stepView;
                expandedArrowView = arrow;
            }
        };
        heading.setOnClickListener(toggle);
        card.setOnClickListener(toggle);
        card.addView(body);
        return card;
    }

    private void createGuides() {
        guides.add(new Guide("शुरुआत और नेविगेशन", "Getting started & navigation",
                "1. ऐप खोलें और सत्यापित ईमेल से साइन इन करें।\n2. नीचे Home, Family, Reminders, Finance और More टैब प्रयोग करें।\n3. सभी फीचर देखने के लिए Home पर ☰ दबाएँ।\n4. घंटी से सूचनाएँ और प्रोफाइल चिन्ह से अपना खाता खोलें।",
                "1. Open the app and sign in with a verified email.\n2. Use Home, Family, Reminders, Finance and More tabs.\n3. Tap ☰ on Home to see every feature.\n4. Use the bell for notifications and the profile icon for your account."));
        guides.add(new Guide("Dashboard और Global Search", "Dashboard & Global Search",
                "1. Home पर live summaries और urgent काम देखें।\n2. किसी card को दबाकर संबंधित module खोलें।\n3. ऊपर Search या menu में Global Search खोलें।\n4. filter चुनें और result दबाकर सीधे record पर जाएँ।",
                "1. See live summaries and urgent work on Home.\n2. Tap a card to open its module.\n3. Open Search at the top or Global Search from the menu.\n4. Choose a filter and tap a result to open its record."));
        guides.add(new Guide("Family Members", "Family members",
                "1. Family टैब खोलें और + दबाएँ।\n2. सदस्य का नाम, संबंध और जरूरी जानकारी भरें।\n3. Save करें; सदस्य card से Edit या Delete चुनें।\n4. सही सदस्य चुनकर shared records assign करें।",
                "1. Open Family and tap +.\n2. Enter the member name, relation and required details.\n3. Save, then use the member card to Edit or Delete.\n4. Select the correct member when assigning shared records."));
        guides.add(new Guide("Family Live, Safety और Safe Places", "Family Live, safety & safe places",
                "1. ☰ से Family Live खोलें और location permission दें।\n2. sharing चालू करके member की live स्थिति देखें।\n3. Safe Places में Home, School या Office जोड़ें।\n4. Safety Centre में alerts देखें; जरूरत पर SOS प्रयोग करें।",
                "1. Open Family Live from ☰ and allow location access.\n2. Turn on sharing to see a member's live status.\n3. Add Home, School or Office under Safe Places.\n4. Review alerts in Safety Centre and use SOS only when needed."));
        guides.add(new Guide("Grocery & Shopping", "Grocery & shopping",
                "1. Grocery खोलें और Daily या Monthly list चुनें।\n2. + से item, मात्रा, unit, category, priority और price भरें।\n3. सदस्य को assign करें; खरीदे जाने पर checkbox दबाएँ।\n4. Floating + या widget से ऐप पूरा खोले बिना item जोड़ें।\n5. Budget में estimated और actual price देखें।",
                "1. Open Grocery and choose Daily or Monthly.\n2. Tap + and enter item, amount, unit, category, priority and price.\n3. Assign a member; tick the item when purchased.\n4. Use Floating + or the widget to add without opening the full app.\n5. Compare estimated and actual price with the budget."));
        guides.add(new Guide("Finance 2.0", "Finance 2.0",
                "1. Finance खोलें और + दबाएँ।\n2. Income या Expense, amount, account और payment method चुनें।\n3. recurring entry या family sharing जरूरत के अनुसार चालू करें।\n4. monthly budget, balance और report जाँचें।\n5. Grocery खरीद को Finance से जोड़ने पर duplicate expense न बनाएँ।",
                "1. Open Finance and tap +.\n2. Select Income or Expense, amount, account and payment method.\n3. Enable recurring entry or family sharing when needed.\n4. Review monthly budget, balance and reports.\n5. Do not add a duplicate expense when a Grocery purchase is linked."));
        guides.add(new Guide("Planner और Reminders", "Planner & reminders",
                "1. Planner/Reminders खोलकर + दबाएँ।\n2. title, date, time, priority और assignee चुनें।\n3. Share with family केवल साझा काम के लिए चालू करें।\n4. काम होने पर status Completed करें।",
                "1. Open Planner or Reminders and tap +.\n2. Choose title, date, time, priority and assignee.\n3. Enable Share with family only for shared tasks.\n4. Mark the status Completed when finished."));
        guides.add(new Guide("Notes collaboration", "Notes collaboration",
                "1. Notes खोलें और नई note बनाएँ।\n2. title, details और checklist जोड़ें।\n3. परिवार के साथ share/assign करना optional है।\n4. checklist items पूरा होने पर tick करें; note को edit या delete कर सकते हैं।",
                "1. Open Notes and create a note.\n2. Add a title, details and checklist.\n3. Sharing or assigning to family is optional.\n4. Tick completed checklist items; notes can be edited or deleted."));
        guides.add(new Guide("Documents Vault", "Documents Vault",
                "1. Documents खोलें और device lock चालू रखें।\n2. + से PDF या image चुनें।\n3. category, expiry date और reminder भरें।\n4. Health, Vehicle या Property record से document link करें।",
                "1. Open Documents and keep device lock enabled.\n2. Tap + to select a PDF or image.\n3. Add category, expiry date and reminder.\n4. Link the document to Health, Vehicle or Property records."));
        guides.add(new Guide("Health Records", "Health records",
                "1. पहले Family में सदस्य जोड़ें।\n2. Health खोलकर सही member चुनें और + दबाएँ।\n3. medicine, appointment, vitals या अन्य record भरें।\n4. document और timeline note जोड़कर Save करें।",
                "1. Add a member in Family first.\n2. Open Health, select the correct member and tap +.\n3. Enter medicine, appointment, vitals or another record.\n4. Add a document and timeline note, then Save."));
        guides.add(new Guide("Vehicle और Property", "Vehicle & property",
                "1. संबंधित module खोलें और + दबाएँ।\n2. owner/member और मुख्य details भरें।\n3. insurance, service, tax या property documents link करें।\n4. timeline/expiry देखकर समय पर update करें।",
                "1. Open the required module and tap +.\n2. Select owner/member and enter the main details.\n3. Link insurance, service, tax or property documents.\n4. Review timeline and expiry dates, then update on time."));
        guides.add(new Guide("Password Vault", "Password Vault",
                "1. Vault खोलने से पहले device lock सेट रखें।\n2. + से login details जोड़ें और मजबूत password रखें।\n3. Chrome/Edge की supported export file Import से चुनें।\n4. Export file को सुरक्षित रखें और काम के बाद हटा दें।",
                "1. Set a device lock before using Vault.\n2. Tap + to add login details and use a strong password.\n3. Choose a supported Chrome/Edge export file under Import.\n4. Keep export files secure and remove them after use."));
        guides.add(new Guide("Backup, Profile और Diagnostics", "Backup, profile & diagnostics",
                "1. Profile में नाम, फोटो और account settings बदलें।\n2. Backup & Restore में सुरक्षित location और password चुनें।\n3. backup बनने के बाद restore test करें।\n4. sync या permission समस्या पर App Diagnostics खोलें।\n5. diagnostic में बताए गए failed item को ठीक करके दोबारा जाँचें।",
                "1. Update name, photo and account settings in Profile.\n2. Choose a safe location and password in Backup & Restore.\n3. Test restore after a backup is created.\n4. Open App Diagnostics for sync or permission problems.\n5. Fix the reported failed item and run the check again."));
        guides.add(new Guide("Privacy और sharing rules", "Privacy & sharing rules",
                "1. निजी record पर Share with family बंद रखें।\n2. केवल सही member को record assign करें।\n3. location sharing जरूरत न होने पर बंद करें।\n4. password, backup और exported files किसी अनजान व्यक्ति को न भेजें।",
                "1. Keep Share with family off for private records.\n2. Assign records only to the correct member.\n3. Turn off location sharing when it is not needed.\n4. Never send passwords, backups or exported files to unknown people."));
    }

    private MaterialCardView card(@ColorRes int fill, @ColorRes int stroke) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(0F);
        card.setCardBackgroundColor(color(fill));
        card.setStrokeColor(color(stroke));
        card.setStrokeWidth(dp(1));
        return card;
    }

    private MaterialButton languageButton(String label) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        return button;
    }

    private void styleLanguageButton(MaterialButton button, boolean selected) {
        button.setTextColor(color(selected ? R.color.fh_on_primary : R.color.fh_primary));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                color(selected ? R.color.fh_primary : R.color.fh_surface)));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(color(R.color.fh_primary)));
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(float size, boolean bold, @ColorRes int colorRes) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color(colorRes));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    @Nullable
    private TextView findTaggedText(View root, String tag) {
        if (tag.equals(root.getTag()) && root instanceof TextView) return (TextView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTaggedText(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int color(@ColorRes int res) { return ContextCompat.getColor(this, res); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }
    private LinearLayout.LayoutParams fixedTop(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(top);
        return params;
    }

    private static final class Guide {
        final String hiTitle;
        final String enTitle;
        final String hiSteps;
        final String enSteps;
        Guide(String hiTitle, String enTitle, String hiSteps, String enSteps) {
            this.hiTitle = hiTitle;
            this.enTitle = enTitle;
            this.hiSteps = hiSteps;
            this.enSteps = enSteps;
        }
    }
}
