package org.example.platform;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestControllerAdvice(basePackages="org.example.platform")
public class PlatformExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason()));}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class) public ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("message","同名记录已存在或该会话已有任务在执行"));}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) public ResponseEntity<?> size(Exception e){return ResponseEntity.status(413).body(Map.of("message","上传文件超过5MB"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.multipart.MultipartException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class}) public ResponseEntity<?> input(Exception e){return ResponseEntity.badRequest().body(Map.of("message","请求格式或参数无效"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> other(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Platform request failed: {}",e.getClass().getSimpleName());return ResponseEntity.status(503).body(Map.of("message",AgentRuntime.safeError(e)));}
}
