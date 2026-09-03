package com.cloudfuze.deltatracker.entity;

/** What a {@link ChangeLogEntry} is about. One value per aggregate that records field edits. */
public enum ChangeLogEntityType {
    PROJECT,
    SERVER,
    COMBINATION
}
