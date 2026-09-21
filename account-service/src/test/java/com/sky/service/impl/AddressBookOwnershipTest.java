package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AddressBookOwnershipTest {

    @AfterEach
    void clearContext() {
        BaseContext.removeCurrentId();
    }

    @Test
    void crossUserReadUpdateDefaultAndDeleteAreRejected() {
        BaseContext.setCurrentId(11L);
        AddressBookMapper mapper = mock(AddressBookMapper.class);
        when(mapper.getByIdAndUserId(99L, 11L)).thenReturn(null);
        when(mapper.deleteByIdAndUserId(99L, 11L)).thenReturn(0);
        AddressBookServiceImpl service = new AddressBookServiceImpl();
        ReflectionTestUtils.setField(service, "addressBookMapper", mapper);
        AddressBook input = new AddressBook(); input.setId(99L);

        assertThrows(AddressBookBusinessException.class, () -> service.getById(99L));
        assertThrows(AddressBookBusinessException.class, () -> service.update(input));
        assertThrows(AddressBookBusinessException.class, () -> service.setDefault(input));
        assertThrows(AddressBookBusinessException.class, () -> service.deleteById(99L));
        verify(mapper, never()).update(any(AddressBook.class));
        verify(mapper, never()).updateIsDefaultByUserId(any(AddressBook.class));
    }
}
