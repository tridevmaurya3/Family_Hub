package com.tridev.familyhub.core.ui.search;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tridev.familyhub.R;

/**
 * Reusable search-bar component for Family Hub.
 *
 * Supported features:
 * - Custom hint text
 * - Initial search query
 * - Search text-change listener
 * - Keyboard search-action listener
 * - Optional voice-search button
 * - Optional filter button
 * - Search query getter and setter
 * - Keyboard focus support
 */
public class SearchBarView extends FrameLayout {

    private ImageView searchIcon;
    private EditText searchEditText;
    private ImageButton voiceButton;
    private ImageButton filterButton;

    @Nullable
    private OnQueryChangeListener queryChangeListener;

    @Nullable
    private OnSearchActionListener searchActionListener;

    @Nullable
    private OnClickListener voiceClickListener;

    @Nullable
    private OnClickListener filterClickListener;

    private boolean internalTextChange;

    public SearchBarView(@NonNull Context context) {
        super(context);
        initialize(context);
    }

    public SearchBarView(
            @NonNull Context context,
            @Nullable AttributeSet attrs
    ) {
        super(context, attrs);
        initialize(context);
    }

    public SearchBarView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(@NonNull Context context) {
        LayoutInflater.from(context).inflate(
                R.layout.view_search_bar,
                this,
                true
        );

        searchIcon = findViewById(R.id.imgSearch);
        searchEditText = findViewById(R.id.edtSearch);
        voiceButton = findViewById(R.id.btnVoice);
        filterButton = findViewById(R.id.btnFilter);

        configureSearchInput();
        configureActionButtons();
    }

    private void configureSearchInput() {
        searchEditText.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text,
                            int start,
                            int count,
                            int after
                    ) {
                        // No action required.
                    }

                    @Override
                    public void onTextChanged(
                            CharSequence text,
                            int start,
                            int before,
                            int count
                    ) {
                        if (internalTextChange) {
                            return;
                        }

                        if (queryChangeListener != null) {
                            queryChangeListener.onQueryChanged(
                                    text == null
                                            ? ""
                                            : text.toString()
                            );
                        }
                    }

                    @Override
                    public void afterTextChanged(Editable editable) {
                        // No action required.
                    }
                }
        );

        searchEditText.setOnEditorActionListener(
                (view, actionId, event) -> {
                    boolean isSearchAction =
                            actionId == EditorInfo.IME_ACTION_SEARCH;

                    boolean isEnterKey =
                            event != null
                                    && event.getKeyCode()
                                    == KeyEvent.KEYCODE_ENTER
                                    && event.getAction()
                                    == KeyEvent.ACTION_UP;

                    if (!isSearchAction && !isEnterKey) {
                        return false;
                    }

                    if (searchActionListener != null) {
                        searchActionListener.onSearch(
                                getQuery()
                        );
                    }

                    return true;
                }
        );
    }

    private void configureActionButtons() {
        voiceButton.setOnClickListener(
                view -> {
                    if (voiceClickListener != null) {
                        voiceClickListener.onClick(view);
                    }
                }
        );

        filterButton.setOnClickListener(
                view -> {
                    if (filterClickListener != null) {
                        filterClickListener.onClick(view);
                    }
                }
        );
    }

    /**
     * Applies a complete SearchBarModel to this view.
     */
    public void setModel(@NonNull SearchBarModel model) {
        setHint(model.getHint());
        setQuery(model.getInitialQuery());
        setVoiceButtonVisible(
                model.isVoiceSearchVisible()
        );
        setFilterButtonVisible(
                model.isFilterVisible()
        );
    }

    /**
     * Sets the search-field hint.
     */
    public void setHint(@Nullable CharSequence hint) {
        searchEditText.setHint(
                hint == null
                        ? ""
                        : hint
        );
    }

    /**
     * Sets the search query without triggering an external query callback.
     */
    public void setQuery(@Nullable CharSequence query) {
        String safeQuery =
                query == null
                        ? ""
                        : query.toString();

        if (safeQuery.equals(
                searchEditText.getText().toString()
        )) {
            return;
        }

        internalTextChange = true;

        searchEditText.setText(safeQuery);
        searchEditText.setSelection(
                searchEditText.getText().length()
        );

        internalTextChange = false;
    }

    /**
     * Returns the current search query.
     */
    @NonNull
    public String getQuery() {
        Editable editable =
                searchEditText.getText();

        return editable == null
                ? ""
                : editable.toString().trim();
    }

    /**
     * Clears the current search query.
     */
    public void clearQuery() {
        setQuery("");
    }

    /**
     * Shows or hides the voice-search button.
     */
    public void setVoiceButtonVisible(boolean visible) {
        voiceButton.setVisibility(
                visible
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    /**
     * Shows or hides the filter button.
     */
    public void setFilterButtonVisible(boolean visible) {
        filterButton.setVisibility(
                visible
                        ? View.VISIBLE
                        : View.GONE
        );
    }

    /**
     * Enables or disables the complete search component.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);

        searchEditText.setEnabled(enabled);
        voiceButton.setEnabled(enabled);
        filterButton.setEnabled(enabled);

        float alpha =
                enabled
                        ? 1.0f
                        : 0.5f;

        searchIcon.setAlpha(alpha);
        searchEditText.setAlpha(alpha);
        voiceButton.setAlpha(alpha);
        filterButton.setAlpha(alpha);
    }

    /**
     * Places the cursor inside the search field.
     */
    public void focusSearchInput() {
        searchEditText.requestFocus();
    }

    /**
     * Removes focus from the search field.
     */
    public void clearSearchFocus() {
        searchEditText.clearFocus();
    }

    public void setOnQueryChangeListener(
            @Nullable OnQueryChangeListener listener
    ) {
        queryChangeListener = listener;
    }

    public void setOnSearchActionListener(
            @Nullable OnSearchActionListener listener
    ) {
        searchActionListener = listener;
    }

    public void setOnVoiceClickListener(
            @Nullable OnClickListener listener
    ) {
        voiceClickListener = listener;
    }

    public void setOnFilterClickListener(
            @Nullable OnClickListener listener
    ) {
        filterClickListener = listener;
    }

    /**
     * Receives search-query changes.
     */
    public interface OnQueryChangeListener {

        void onQueryChanged(
                @NonNull String query
        );
    }

    /**
     * Receives keyboard search actions.
     */
    public interface OnSearchActionListener {

        void onSearch(
                @NonNull String query
        );
    }
}
