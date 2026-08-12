package com.tridev.familyhub.feature.grocery;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GroceryQuantityCalculatorTest {
    @Test public void addsMassAcrossUnits() {
        assertEquals("1.5 kg", GroceryQuantityCalculator.add("500 g", "1 kg"));
    }
    @Test public void addsVolumeAcrossUnits() {
        assertEquals("1.5 L", GroceryQuantityCalculator.add("500 ml", "1 L"));
    }
    @Test public void preservesDozenForDozenPurchases() {
        assertEquals("4 dozen", GroceryQuantityCalculator.add("2 dozen", "2 dozen"));
    }
    @Test public void convertsDozenAndPieces() {
        assertEquals("18 pcs", GroceryQuantityCalculator.add("1 dozen", "6 pcs"));
    }
}
