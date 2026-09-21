package com.sky.contract.account;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Address snapshot required when commerce creates an order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountAddressView {
    private Long id;
    private Long userId;
    private String consignee;
    private String phone;
    private String detail;
}
