package com.sky.websocket;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {
    private static final Map<String, Session> SESSIONS=new ConcurrentHashMap<>();
    @OnOpen public void open(Session session,@PathParam("sid") String sid){ SESSIONS.put(sid,session); }
    @OnClose public void close(@PathParam("sid") String sid){ SESSIONS.remove(sid); }
    @OnError public void error(Session session,Throwable error){ if(session!=null) SESSIONS.values().remove(session); }
    @OnMessage public void message(String ignored) { }
    public void broadcast(String message){ SESSIONS.values().forEach(s->{ try { synchronized(s){s.getBasicRemote().sendText(message);} } catch(Exception ignored){} }); }
}
