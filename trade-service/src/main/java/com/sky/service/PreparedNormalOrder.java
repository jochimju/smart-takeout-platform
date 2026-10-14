package com.sky.service;
import com.sky.dto.OrderSubmitMessageDTO;
import com.sky.entity.ShoppingCart;
import com.sky.contract.account.AccountAddressView;
import com.sky.contract.catalog.ProductQuote;
import java.util.List;
import java.util.Map;
public record PreparedNormalOrder(OrderSubmitMessageDTO command,AccountAddressView address,List<ShoppingCart> items,Map<String,ProductQuote> quotes){}
