package com.sky.account;

import com.sky.entity.AddressBook;
import com.sky.entity.User;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountUserView;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.PermissionMapper;
import com.sky.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/account")
@RequiredArgsConstructor
public class InternalAccountController {
    private final UserMapper users;
    private final AddressBookMapper addresses;
    private final PermissionMapper permissions;

    @GetMapping("/users/{id}")
    public AccountUserView user(@PathVariable Long id) {
        User user = users.getById(id);
        return user == null ? null : new AccountUserView(user.getOpenid());
    }

    @GetMapping("/addresses/{userId}/{id}")
    public AccountAddressView address(@PathVariable Long userId, @PathVariable Long id) {
        AddressBook address = addresses.getByIdAndUserId(id, userId);
        return address == null ? null : new AccountAddressView(address.getId(), address.getUserId(),
                address.getConsignee(), address.getPhone(), address.getDetail());
    }

    @GetMapping("/users/count")
    public Integer countUsers(@RequestParam(required = false) String begin,
                              @RequestParam(required = false) String end) {
        Map<String, LocalDateTime> range = new HashMap<>();
        if (begin != null && !begin.isBlank()) range.put("begin", LocalDateTime.parse(begin));
        if (end != null && !end.isBlank()) range.put("end", LocalDateTime.parse(end));
        return users.countByMap(range);
    }

    @GetMapping("/permissions/check")
    public Boolean permitted(@RequestParam Long employeeId,
                             @RequestParam String method,
                             @RequestParam String path) {
        if (permissions.countPermission(method, path) == 0) return true;
        return permissions.countEmployeePermission(employeeId, method, path) > 0;
    }
}
