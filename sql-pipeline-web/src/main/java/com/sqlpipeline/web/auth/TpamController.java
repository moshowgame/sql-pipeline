package com.sqlpipeline.web.auth;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.web.dto.TpamGrantReq;
import com.sqlpipeline.web.dto.TpamRevokeReq;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * self-TPAM 自助临时账号接口（support 场景）：
 * <ul>
 *   <li>POST /api/auth/tpam/grant —— 申请临时账号（写操作，仅 ADMIN，VIEWER 403）；</li>
 *   <li>POST /api/auth/tpam/revoke —— 提前吊销；</li>
 *   <li>GET  /api/auth/tpam/list  —— 现存临时账号列表（可查询）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/tpam")
@RequiredArgsConstructor
public class TpamController {

    private final TempUserService tempUserService;
    private final AuthInterceptor authInterceptor;

    /** 申请：生成 staffid_tpam 账号 + 32 位随机密码，明文仅本次响应返回。 */
    @PostMapping("/grant")
    public R<TempUserService.TpamGrantResult> grant(@Valid @RequestBody TpamGrantReq req) {
        return R.ok(tempUserService.grant(req.crInNo(), req.staffId(), req.role(), currentOperator()));
    }

    /** 吊销：删除临时账号，释放 staffid。 */
    @PostMapping("/revoke")
    public R<Void> revoke(@Valid @RequestBody TpamRevokeReq req) {
        tempUserService.revoke(req.username(), currentOperator());
        return R.ok();
    }

    /** 现存临时账号列表。 */
    @GetMapping("/list")
    public R<?> list() {
        return R.ok(tempUserService.list());
    }

    private String currentOperator() {
        // AuthInterceptor 已把用户名放入 request attribute，这里直接取
        return (String) RequestContextHolder.currentRequestAttributes()
                .getAttribute(AuthInterceptor.ATTR_USERNAME, RequestAttributes.SCOPE_REQUEST);
    }
}
