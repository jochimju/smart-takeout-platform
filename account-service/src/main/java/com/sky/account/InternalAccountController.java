package com.sky.account;

import com.sky.entity.AddressBook;
import com.sky.entity.User;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.account.AccountAddressLookup;
import com.sky.contract.account.AccountUserView;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.PermissionMapper;
import com.sky.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/account")
@RequiredArgsConstructor
public class InternalAccountController {
    private final UserMapper users;
    @org.springframework.beans.factory.annotation.Autowired private LocalAccountAccess access;
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

    @PostMapping("/addresses/batch")
    public java.util.List<AccountAddressView> addresses(@RequestBody java.util.List<AccountAddressLookup> lookups) {
        if (lookups == null || lookups.isEmpty() || lookups.size() > 16 ||
                lookups.stream().anyMatch(row -> row == null || row.userId() == null || row.addressId() == null))
            throw new IllegalArgumentException("Invalid address batch");
        var owned = new java.util.HashSet<>(lookups);
        return addresses.getByIds(lookups.stream().map(AccountAddressLookup::addressId).distinct().toList())
                .stream().filter(row -> owned.contains(new AccountAddressLookup(row.getUserId(), row.getId())))
                .map(row -> new AccountAddressView(row.getId(), row.getUserId(), row.getConsignee(), row.getPhone(), row.getDetail()))
                .toList();
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
        return access.permitted(employeeId, method, path);
    }

    @PostMapping("/sessions/check")
    public Boolean valid(@RequestBody com.sky.contract.account.SessionCheck check) {
        return check != null && access.valid(check.token(), check.admin());
    }
}
