package com.learning.spring.financialdashboard.model;

/** Why a bucket's balance changed. Every change is kept, so a bucket's history is never lost. */
public enum EntryKind {
    OPENING,     // money the bucket already held when it was added
    ADJUST,      // a correction to the money held, made by editing the bucket
    INTEREST,    // interest the bank credited to the Ledger, added to the buffer
    FUND,        // money added when the Ledger was funded
    PAY,         // a bill paid from the bucket
    MOVE_IN,     // moved in from another bucket
    MOVE_OUT,    // moved out to another bucket
    CLOSE_OUT,   // what was left when the bucket was closed
    CLOSE_IN     // the leftover of a closed bucket arriving here
}
