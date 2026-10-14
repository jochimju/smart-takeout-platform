package com.sky.account;

import com.sky.mapper.PermissionMapper;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Service
@RequiredArgsConstructor
public class PermissionAccess {
    private final PermissionMapper permissions;
    public boolean permitted(Long employeeId, String method, String path) {
        if (employeeId == null || method == null || path == null || !path.startsWith("/")) return false;
        String actualMethod = "HEAD".equals(method) ? "GET" : method;
        var target = PathContainer.parsePath(path);
        record Match(long id, PathPattern pattern) { }
        var matches = permissions.routes(actualMethod).stream().map(row ->
                new Match(row.id(), PathPatternParser.defaultInstance.parse(row.path())))
                .filter(row -> row.pattern().matches(target))
                .sorted(Comparator.comparing(Match::pattern, PathPattern.SPECIFICITY_COMPARATOR)).toList();
        // Select the most specific configured route; a broad grant cannot bypass a narrower restriction.
        return !matches.isEmpty() && permissions.granted(employeeId, matches.get(0).id()) > 0;
    }
}
