package com.tridev.familyhub.feature.grocery.widget;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GroceryWidgetExecutors {
    static final ExecutorService DATABASE = Executors.newSingleThreadExecutor();
    private GroceryWidgetExecutors() { }
}
