package com.example.coppergolem.entity;

/** Owner approval for the golem taking/crafting gear. Implemented by the controller. */
public interface ApprovalGate {
    /** @return true if the owner approves acquiring this item. */
    boolean request(String itemDescription);
}
