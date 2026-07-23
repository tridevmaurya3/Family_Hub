package com.tridev.familyhub.data.model;

/**
 * Dashboard statistics model.
 *
 * This class contains all values displayed
 * on the Family Hub dashboard.
 */
public class DashboardStats {

    // Family
    public int totalMembers;
    public int maleMembers;
    public int femaleMembers;
    public int children;

    // Finance
    public double income;
    public double expense;
    public double balance;

    // Health
    public int healthAlerts;

    // Documents
    public int documents;

    // Reminders
    public int upcomingReminders;

    // Action center
    public int plannerOpen;
    public int plannerCompleted;
    public int groceryPending;
    public int groceryPurchased;
    public int documentsExpiringSoon;
    public int vehiclesDueSoon;
    public int activeNotes;
    public int pinnedNotes;
    public int familyLiveSharing;

    public DashboardStats() {
    }

    public int getTotalMembers() {
        return totalMembers;
    }

    public void setTotalMembers(int totalMembers) {
        this.totalMembers = totalMembers;
    }

    public int getMaleMembers() {
        return maleMembers;
    }

    public void setMaleMembers(int maleMembers) {
        this.maleMembers = maleMembers;
    }

    public int getFemaleMembers() {
        return femaleMembers;
    }

    public void setFemaleMembers(int femaleMembers) {
        this.femaleMembers = femaleMembers;
    }

    public int getChildren() {
        return children;
    }

    public void setChildren(int children) {
        this.children = children;
    }

    public double getIncome() {
        return income;
    }

    public void setIncome(double income) {
        this.income = income;
    }

    public double getExpense() {
        return expense;
    }

    public void setExpense(double expense) {
        this.expense = expense;
    }

    public double getBalance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public int getHealthAlerts() {
        return healthAlerts;
    }

    public void setHealthAlerts(int healthAlerts) {
        this.healthAlerts = healthAlerts;
    }

    public int getDocuments() {
        return documents;
    }

    public void setDocuments(int documents) {
        this.documents = documents;
    }

    public int getUpcomingReminders() {
        return upcomingReminders;
    }

    public void setUpcomingReminders(int upcomingReminders) {
        this.upcomingReminders = upcomingReminders;
    }

    public int getPlannerOpen() {
        return plannerOpen;
    }

    public void setPlannerOpen(int plannerOpen) {
        this.plannerOpen = plannerOpen;
    }

    public int getPlannerCompleted() {
        return plannerCompleted;
    }

    public void setPlannerCompleted(int plannerCompleted) {
        this.plannerCompleted = plannerCompleted;
    }

    public int getGroceryPending() {
        return groceryPending;
    }

    public void setGroceryPending(int groceryPending) {
        this.groceryPending = groceryPending;
    }

    public int getGroceryPurchased() {
        return groceryPurchased;
    }

    public void setGroceryPurchased(int groceryPurchased) {
        this.groceryPurchased = groceryPurchased;
    }

    public int getDocumentsExpiringSoon() {
        return documentsExpiringSoon;
    }

    public void setDocumentsExpiringSoon(int documentsExpiringSoon) {
        this.documentsExpiringSoon = documentsExpiringSoon;
    }

    public int getVehiclesDueSoon() {
        return vehiclesDueSoon;
    }

    public void setVehiclesDueSoon(int vehiclesDueSoon) {
        this.vehiclesDueSoon = vehiclesDueSoon;
    }

    public int getActiveNotes() {
        return activeNotes;
    }

    public void setActiveNotes(int activeNotes) {
        this.activeNotes = activeNotes;
    }

    public int getPinnedNotes() {
        return pinnedNotes;
    }

    public void setPinnedNotes(int pinnedNotes) {
        this.pinnedNotes = pinnedNotes;
    }

    public int getFamilyLiveSharing() {
        return familyLiveSharing;
    }

    public void setFamilyLiveSharing(int familyLiveSharing) {
        this.familyLiveSharing = familyLiveSharing;
    }
}
