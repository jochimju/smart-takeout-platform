package com.sky.service.mq;

import com.sky.constant.MqConstant;
import org.springframework.amqp.core.*;
import java.util.*;

/** Fixed queue TTLs avoid short expirations sitting behind longer messages.
 * Round up at most 10 seconds; the order's absolute deadline remains authoritative.
 * Old queues remain declared to drain already published messages.
 */
public final class TimeoutRouting {
    private TimeoutRouting() {}
    public static List<Integer> seconds() {
        var values=new ArrayList<Integer>();
        for(int i=1;i<10;i++)values.add(i);
        for(int i=10;i<=900;i+=10)values.add(i);
        return List.copyOf(values);
    }
    public static String route(long remainingMillis) {
        if(remainingMillis<=0)return MqConstant.ORDER_DEAD_ROUTING_KEY;
        if(remainingMillis>900000)throw new IllegalArgumentException("Timeout exceeds fifteen minutes");
        long seconds=(remainingMillis+999)/1000;
        return "order.delay.v4."+(seconds<10?seconds:((seconds+9)/10)*10);
    }
    public static Declarables declarations(DirectExchange exchange) {
        var declarations=new ArrayList<Declarable>();
        for(int seconds:seconds()) {
            String route="order.delay.v4."+seconds;
            var queue=QueueBuilder.durable(route+".queue").ttl(seconds*1000)
                .deadLetterExchange(exchange.getName()).deadLetterRoutingKey(MqConstant.ORDER_DEAD_ROUTING_KEY).build();
            declarations.add(queue);declarations.add(BindingBuilder.bind(queue).to(exchange).with(route));
        }
        return new Declarables(declarations);
    }
}
