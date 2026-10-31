package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.service.MediaService;
import com.safesphere.backend.social.service.MediaService.Uploaded;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13B: photos. The body of an upload is the image itself (image/jpeg or image/png, at most 5 MB). Downloads are
 * always the server's re-encoded JPEG, never cached by anything in between (private, no-store).
 * Phase 17C: videos. The body is the MP4 itself (video/mp4, at most 50 MB, at most 60 s); then its poster frame
 * (image/jpeg) to /{id}/poster. GET /{id} is always an image (a video's poster); GET /{id}/video is the stripped MP4,
 * streamed from its file with byte ranges (players seek with them). DELETE /{id} removes an upload no post uses.
 * The content type of every answer comes from the row's kind, never from the file.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private static final MediaType MP4 = MediaType.parseMediaType("video/mp4");

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping(consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    public Uploaded upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        return media.upload(CurrentUser.id(jwt), request.getInputStream());
    }

    @PostMapping(consumes = "video/mp4")
    @ResponseStatus(HttpStatus.CREATED)
    public Uploaded uploadVideo(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        return media.uploadVideo(CurrentUser.id(jwt), request.getInputStream(), request.getContentLengthLong());
    }

    @PostMapping(path = "/{mediaId}/poster", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public Uploaded poster(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId, HttpServletRequest request)
            throws IOException {
        return media.uploadPoster(CurrentUser.id(jwt), mediaId, request.getInputStream());
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        media.deleteUnattached(CurrentUser.id(jwt), mediaId);
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        byte[] bytes = media.download(CurrentUser.id(jwt), mediaId).bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }

    @GetMapping("/{mediaId}/video")
    public ResponseEntity<Resource> video(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        return videoAnswer(media.video(CurrentUser.id(jwt), mediaId));
    }

    /** Also used by the moderators' case route: a file resource, so Spring answers Range requests with 206. */
    public static ResponseEntity<Resource> videoAnswer(Path file) {
        return ResponseEntity.ok()
                .contentType(MP4)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(file));
    }
}
