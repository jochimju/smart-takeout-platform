package com.sky.controller.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves menu images packaged under classpath:/static when running the
 * application as an executable JAR.
 */
@RestController
public class StaticImageController {

    @GetMapping("/dishes/{fileName:.+}")
    public ResponseEntity<Resource> dishImage(@PathVariable String fileName) {
        return image("dishes", fileName);
    }

    @GetMapping("/setmeals/{fileName:.+}")
    public ResponseEntity<Resource> setmealImage(@PathVariable String fileName) {
        return image("setmeals", fileName);
    }

    private ResponseEntity<Resource> image(String directory, String fileName) {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new ClassPathResource("static/" + directory + "/" + fileName);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        MediaType contentType = MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }
}
