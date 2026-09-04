package com.cloudfuze.deltatracker.dto;

import lombok.Getter;
import lombok.Setter;

// One customer user whose Drive changes were counted: the Users._id the filter ran on, and the
// primaryEmail that id belongs to.
//
// Both, not just the email. The id is what DriveChangeIdDetails.userId actually holds -- it is the
// value somebody has to paste into Metabase to reproduce a figure by hand -- while the email is the
// only part a reader recognises. Showing the email alone would make the number untraceable; showing
// the id alone would make it unreadable.
@Getter
@Setter
public class MetabaseUserDto {

    private String userId;
    private String email;

    public MetabaseUserDto() {
    }

    public MetabaseUserDto(String userId, String email) {
        this.userId = userId;
        this.email = email;
    }
}
