package org.example.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestControllerAdvice(assignableTypes={ConsoleController.class,ConsoleDocumentsController.class,ConsoleObservabilityController.class,FileUploadController.class,DiagnosisController.class})
public class ConsoleExceptionHandler {
    @ExceptionHandler(org.springframework.web.ErrorResponseException.class) public ResponseEntity<?> webError(org.springframework.web.ErrorResponseException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message","请求参数不完整或不合法"));}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.multipart.MultipartException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class}) public ResponseEntity<?> badInput(Exception e){return ResponseEntity.badRequest().body(Map.of("message","请检查请求格式和必填参数"));}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) public ResponseEntity<?> tooLarge(Exception e){return ResponseEntity.status(413).body(Map.of("message","上传文件超过大小限制"));}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Console request failed",e);return ResponseEntity.status(503).body(Map.of("message","服务暂时不可用，请稍后重试；已有数据未被宣称处理成功"));}
}
