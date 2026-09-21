package com.sky.contract.account;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fields that commerce needs from an account, independent of the account table. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountUserView {
    private String openid;
}
