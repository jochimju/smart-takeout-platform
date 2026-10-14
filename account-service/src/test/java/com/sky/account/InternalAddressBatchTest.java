package com.sky.account;

import com.sky.contract.account.AccountAddressLookup;
import com.sky.entity.AddressBook;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.PermissionMapper;
import com.sky.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalAddressBatchTest {
    @Test void onlyRequestedOwnerReceivesAddressEvenWhenAnIdExistsForAnotherUser() {
        var mapper=mock(AddressBookMapper.class);
        var own=new AddressBook();own.setId(101L);own.setUserId(1L);own.setConsignee("owner");
        var foreign=new AddressBook();foreign.setId(202L);foreign.setUserId(9L);foreign.setConsignee("foreign");
        when(mapper.getByIds(List.of(101L,202L))).thenReturn(List.of(own,foreign));
        var controller=new InternalAccountController(mock(UserMapper.class),mapper,mock(PermissionMapper.class));
        var result=controller.addresses(List.of(new AccountAddressLookup(1L,101L),new AccountAddressLookup(2L,202L)));
        assertEquals(1,result.size());assertEquals(1L,result.get(0).getUserId());assertEquals(101L,result.get(0).getId());
        assertThrows(IllegalArgumentException.class,()->controller.addresses(List.of()));
    }
}
