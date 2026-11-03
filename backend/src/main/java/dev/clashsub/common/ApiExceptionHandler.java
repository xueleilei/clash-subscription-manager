package dev.clashsub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResult<Void>> expected(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(ApiResult.failure(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResult<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResult.failure("A0001", "输入格式不正确"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult<Void>> unexpected(Exception exception) {
        var response = ApiResult.failure("B0001", "服务暂时无法处理请求");
        // Exception messages may contain URLs, SQL parameters or parsed secrets.
        LOG.error("Request failed: trace={}, type={}", response.traceId(), exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(response);
    }
}
