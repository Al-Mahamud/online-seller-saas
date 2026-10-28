package com.mojud.tenancy;

/** The business's team, for billing: staff and managers, with invites still open (the owner is not counted). */
public interface TeamSize {

    long staffSeatsUsed();
}
