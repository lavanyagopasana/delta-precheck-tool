package com.cloudfuze.deltatracker.dto;

import com.cloudfuze.deltatracker.entity.ProductType;
import lombok.Getter;
import lombok.Setter;

// Editing a Server's product type after creation -- the only field this flow needs to change today.
@Getter
@Setter
public class UpdateServerRequest {

    private ProductType productType;
}
