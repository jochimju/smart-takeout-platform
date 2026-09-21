package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.entity.AddressBook;
import com.sky.result.Result;
import com.sky.service.AddressBookService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/user/addressBook")
@Api(tags = "address book api")
public class AddressBookController {

    @Autowired
    private AddressBookService addressBookService;

    /**
     * 闁哄被鍎撮妤勩亹閹惧啿顤呴柣褑顕х紞宥夋偨閵婏箑鐓曢柣銊ュ婢у秹寮垫径濠冨嬀闁秆€鍋撳ǎ鍥ｅ墲娴?
     *
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("list address book")
    public Result<List<AddressBook>> list() {
        AddressBook addressBook = new AddressBook();
        addressBook.setUserId(BaseContext.getCurrentId());
        List<AddressBook> list = addressBookService.list(addressBook);
        return Result.success(list);
    }

    /**
     * 闁哄倹婢橀·鍐捶閺夋寧绲?
     *
     * @param addressBook
     * @return
     */
    @PostMapping
    @ApiOperation("add address")
    public Result save(@RequestBody AddressBook addressBook) {
        addressBookService.save(addressBook);
        return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("get default address")
    public Result<AddressBook> getById(@PathVariable Long id) {
        AddressBook addressBook = addressBookService.getById(id);
        return Result.success(addressBook);
    }

    /**
     * 闁哄秷顫夊畵涔甦濞ｅ浂鍠楅弫濂稿捶閺夋寧绲?
     *
     * @param addressBook
     * @return
     */
    @PutMapping
    @ApiOperation("update address")
    public Result update(@RequestBody AddressBook addressBook) {
        addressBookService.update(addressBook);
        return Result.success();
    }

    /**
     * 閻犱礁澧介悿鍡橆渶濡鍚囬柛锔芥緲濞?
     *
     * @param addressBook
     * @return
     */
    @PutMapping("/default")
    @ApiOperation("set default address")
    public Result setDefault(@RequestBody AddressBook addressBook) {
        addressBookService.setDefault(addressBook);
        return Result.success();
    }

    /**
     * 闁哄秷顫夊畵涔甦闁告帞濞€濞呭酣宕烽弶鎸庣祷
     *
     * @param id
     * @return
     */
    @DeleteMapping
    @ApiOperation("闁哄秷顫夊畵涔甦闁告帞濞€濞呭酣宕烽弶鎸庣祷")
    public Result deleteById(Long id) {
        addressBookService.deleteById(id);
        return Result.success();
    }

    /**
     * 闁哄被鍎撮妤侇渶濡鍚囬柛锔芥緲濞?
     */
    @GetMapping("default")
    @ApiOperation("get address by id")
    public Result<AddressBook> getDefault() {
        //SQL:select * from address_book where user_id = ? and is_default = 1
        AddressBook addressBook = new AddressBook();
        addressBook.setIsDefault(1);
        addressBook.setUserId(BaseContext.getCurrentId());
        List<AddressBook> list = addressBookService.list(addressBook);

        if (list != null && list.size() == 1) {
            return Result.success(list.get(0));
        }

        return Result.error("cannot delete default address");
    }

}
