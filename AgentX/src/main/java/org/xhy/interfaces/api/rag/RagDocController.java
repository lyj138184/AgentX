package org.xhy.interfaces.api.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.xhy.interfaces.api.common.Result;
import org.xhy.interfaces.dto.rag.RagUploadRequest;

/**
 * 数据
 * @author shilong.zang
 * @date 18:54 <br/>
 */
@RestController
@RequestMapping("/rag/doc")
public class RagDocController {

    @PostMapping("/ocr")
    public Result<Void> upload(@RequestBody List<String> fileIds) {
        //fileService.upload(request);
        return Result.success();
    }

}
