package com.example.bank.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BankException.class)
    public ResponseEntity<ApiError> handleBank(BankException ex, HttpServletRequest req) {
        return build(ex.getCode().getStatus(), ex.getCode().name(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Geçersiz istek", req, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(), "Geçersiz istek", req, details);
    }

    /** İstemci ?sort=olmayanAlan gibi geçersiz bir sıralama alanı gönderirse. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> handleBadSort(PropertyReferenceException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                "Geçersiz sıralama alanı: " + ex.getPropertyName(), req, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadable(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                "İstek gövdesi veya parametre okunamadı", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED.name(), "Bu işlem için yetkin yok", req, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                         HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT.name(),
                "Kayıt aynı anda başka bir işlem tarafından değiştirildi, tekrar dene", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Veri bütünlüğü ihlali: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, ErrorCode.CONFLICT.name(), "İşlem veri kuralları nedeniyle reddedildi",
                req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest req) {
        // Spring'in kendi hataları (404, 405, eksik header vb.) kendi durum kodunu taşır.
        if (ex instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return build(status, status.name(), er.getBody().getDetail(), req, null);
        }
        log.error("Beklenmeyen hata", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "Beklenmeyen bir hata oluştu", req, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest req, List<String> details) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), code, message, req.getRequestURI(), details));
    }
}
