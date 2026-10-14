package com.sky.payment;

import com.sky.utils.WeChatPayUtil;
import com.sky.utils.properties.WeChatProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PaymentReconciliation {
    private final JdbcTemplate jdbc;
    private final WeChatPayUtil wechat;
    private final WeChatProperties properties;
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> download(LocalDate date) throws Exception {
        return compare(date,wechat.downloadTradeBill(date),"CHANNEL_VERIFIED");
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> upload(LocalDate date,String csv) throws Exception { return compare(date,csv,"UPLOAD"); }
    private Map<String,Object> compare(LocalDate date,String csv,String source) throws Exception {
        if(date==null || date.isAfter(LocalDate.now()) || csv==null || csv.getBytes(StandardCharsets.UTF_8).length>8*1024*1024)
            throw new IllegalArgumentException("Invalid bill date or size");
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((source+"\n"+csv).getBytes(StandardCharsets.UTF_8)));
        // Unique insert and row lock make duplicate uploads atomic, even when requests arrive concurrently.
        jdbc.update("insert into payment_reconciliation_run(bill_date,file_sha256,source,state) values(?,?,?,'RUNNING') on duplicate key update id=id",date,digest,source);
        var run=jdbc.queryForMap("select * from payment_reconciliation_run where bill_date=? and file_sha256=? for update",date,digest);
        if(!"RUNNING".equals(run.get("state"))) return run;
        long id=((Number)run.get("id")).longValue();
        String[] lines=csv.replace("\uFEFF","").split("\\r?\\n");
        if(lines.length==0) throw new IllegalArgumentException("Empty bill");
        List<String> headers=row(lines[0]); Set<String> required=Set.of("交易时间","商户号","公众账号ID","商户订单号","微信订单号","交易状态","货币种类");
        if(new HashSet<>(headers).size()!=headers.size() || !headers.containsAll(required)
                || (!headers.contains("订单金额") && !headers.contains("总金额"))) throw new IllegalArgumentException("Unsupported trade bill columns");
        Set<String> seen=new HashSet<>(),entries=new HashSet<>();int records=0,differences=0;
        for(int i=1;i<lines.length;i++) {
            if(lines[i].isBlank()) continue;
            if(lines[i].startsWith("总交易单数")) break; // WeChat CSV summary section.
            List<String> cells=row(lines[i]);
            if(cells.size()!=headers.size()) throw new IllegalArgumentException("Malformed trade bill row "+(i+1));
            Map<String,String> data=new HashMap<>(); for(int c=0;c<headers.size();c++) data.put(headers.get(c),cells.get(c));
            var transactionTime=java.time.LocalDateTime.parse(data.get("交易时间"),java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT));
            if(!date.equals(transactionTime.toLocalDate())) throw new IllegalArgumentException("Trade bill row date mismatch");
            if(!Objects.equals(properties.getMchid(),data.get("商户号")) || !Objects.equals(properties.getAppid(),data.get("公众账号ID")))
                throw new SecurityException("Trade bill merchant/AppID mismatch");
            String number=data.get("商户订单号"),transaction=data.get("微信订单号"),state=data.get("交易状态");records++;
            if(!number.matches("[a-zA-Z0-9_-]{1,64}") || transaction.isBlank()) throw new IllegalArgumentException("Invalid bill identifiers");
            if(!Set.of("SUCCESS","REFUND","成功","转入退款","退款").contains(state)) { diff(id,number,"UNKNOWN_BILL_STATE",state);differences++;continue; }
            boolean refund=Set.of("REFUND","转入退款","退款").contains(state);
            // ALL bills can contain both payment and refund rows for the same order.
            if(!refund) seen.add(number);
            String entry=number+":"+(refund?"REFUND:"+data.getOrDefault("商户退款单号",""):"SUCCESS");
            if(!entries.add(entry)) { diff(id,number,"DUPLICATE_BILL_ENTRY","Repeated payment/refund entry in bill");differences++;continue; }
            BigDecimal amount=new BigDecimal(data.getOrDefault("订单金额",data.get("总金额")));
            if(amount.signum()<=0 || amount.stripTrailingZeros().scale()>2 || !"CNY".equals(data.get("货币种类"))) throw new IllegalArgumentException("Invalid bill amount/currency");
            var receipts=jdbc.queryForList("select transaction_id,amount from order_payment_receipt where order_number=? and mock_payment=0",number);
            if(receipts.isEmpty()) { diff(id,number,"MISSING_LOCAL_RECEIPT","Channel reports payment but local receipt is absent");differences++;continue; }
            var receipt=receipts.get(0);
            if(!transaction.equals(receipt.get("transaction_id")) || amount.compareTo((BigDecimal)receipt.get("amount"))!=0) {
                diff(id,number,"RECEIPT_MISMATCH","Transaction id or amount differs");differences++;
            }
            if(refund) {
                String refunded=data.getOrDefault("申请退款金额",data.get("退款金额"));
                if(refunded==null || new BigDecimal(refunded).compareTo(amount)!=0 || !refunded(number)) {
                    diff(id,number,"REFUND_MISMATCH","Refund amount or local completion differs");differences++;
                }
            }
        }
        for(var receipt:jdbc.queryForList("select order_number from order_payment_receipt where mock_payment=0 and channel_paid_at>=? and channel_paid_at<?",date.atStartOfDay(),date.plusDays(1).atStartOfDay())) {
            String number=(String)receipt.get("order_number");
            if(!seen.contains(number)) { diff(id,number,"MISSING_CHANNEL_ENTRY","Local receipt has no entry in the bill");differences++; }
        }
        jdbc.update("update payment_reconciliation_run set state=?,row_count=?,difference_count=? where id=?",differences==0?"MATCHED":"DIFFERENCES",records,differences,id);
        return jdbc.queryForMap("select * from payment_reconciliation_run where id=?",id);
    }
    private boolean refunded(String number) {
        String sql=number.startsWith("RP")?"select count(*) from red_packet_purchase_order where order_no=? and status=4":"select count(*) from orders where number=? and status=6 and pay_status=2";
        return jdbc.queryForObject(sql,Integer.class,number)>0;
    }
    private void diff(long run,String number,String kind,String detail) {
        jdbc.update("insert into payment_reconciliation_difference(run_id,order_number,kind,detail) values(?,?,?,?)",run,number,kind,detail);
    }
    /** CSV quotes are parsed without evaluating spreadsheet expressions; WeChat backtick prefixes are removed. */
    static List<String> row(String line) {
        List<String> values=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') { if(quoted && i+1<line.length() && line.charAt(i+1)=='"') {cell.append('"');i++;} else quoted=!quoted; }
            else if(c==',' && !quoted) {values.add(clean(cell.toString()));cell.setLength(0);} else cell.append(c);
        }
        if(quoted) throw new IllegalArgumentException("Unclosed CSV quote");values.add(clean(cell.toString()));return values;
    }
    private static String clean(String value) { return value.trim().replaceFirst("^`",""); }
    public List<Map<String,Object>> runs() { return jdbc.queryForList("select * from payment_reconciliation_run order by id desc limit 100"); }
    public List<Map<String,Object>> differences(long run) { return jdbc.queryForList("select * from payment_reconciliation_difference where run_id=? order by id limit 1000",run); }
}
