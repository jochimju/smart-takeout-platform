package com.sky.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint(value = "/ws/{sid}", configurator = WebSocketAuthConfigurator.class)
public class WebSocketServer {
    private static final Map<String, Session> SESSIONS=new ConcurrentHashMap<>();
    @OnOpen public void open(Session session,@PathParam("sid") String sid){
        if (Boolean.TRUE.equals(session.getUserProperties().get(WebSocketAuthConfigurator.AUTH_REJECTED))) {
            try { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "unauthorized")); }
            catch (Exception ignored) { }
            return;
        }
        SESSIONS.put(sid,session);
    }
    @OnClose public void close(@PathParam("sid") String sid){ SESSIONS.remove(sid); }
    @OnError public void error(Session session,Throwable error){ if(session!=null) SESSIONS.values().remove(session); }
    @OnMessage public void message(String ignored) { }
    public void broadcast(String message){ SESSIONS.values().forEach(s->{
        if (!active(s)) { closeInactive(s); return; }
        try { synchronized(s){s.getBasicRemote().sendText(message);} } catch(Exception ignored){}
    }); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void checkSessions() { SESSIONS.values().forEach(s -> { if (!active(s)) closeInactive(s); }); }
    private boolean active(Session session) {
        try {
            var context=SpringContext.get(); var jwt=context.getBean(com.sky.utils.properties.JwtProperties.class);
            // Browser order-alert connections use their own query token, never shared configurator identity state.
            var tokens=session.getRequestParameterMap().get(jwt.getAdminTokenName());
            if (tokens==null || tokens.isEmpty()) return false;
            String token=tokens.get(0);
            long id=Long.parseLong(com.sky.utils.JwtUtil.parseJWT(jwt.getAdminSecretKey(),token).get("empId").toString());
            return context.getBean(NotificationAccountAccess.class).allowed(token,id,session.getRequestURI().getPath());
        } catch (RuntimeException ex) { return false; }
    }
    private void closeInactive(Session session) {
        SESSIONS.values().remove(session);
        try { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,"session expired or revoked")); }
        catch (Exception ignored) { }
    }
}
