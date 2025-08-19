package org.xhy.application.rag.assembler;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.xhy.application.rag.dto.DocumentUnitDTO;
import org.xhy.application.rag.dto.UpdateDocumentUnitRequest;
import org.xhy.domain.rag.model.DocumentUnitEntity;

/** 文档单元转换器
 * 
 * @author shilong.zang */
public class DocumentUnitAssembler {

    /** Convert Entity to DTO using BeanUtils */
    public static DocumentUnitDTO toDTO(DocumentUnitEntity entity) {
        if (entity == null) {
            return null;
        }
        DocumentUnitDTO dto = new DocumentUnitDTO();
        BeanUtils.copyProperties(entity, dto);

        // 时间格式化
        if (entity.getCreatedAt() != null) {
            dto.setCreatedAt(entity.getCreatedAt().toString());
        }
        if (entity.getUpdatedAt() != null) {
            dto.setUpdatedAt(entity.getUpdatedAt().toString());
        }

        return dto;
    }

    /** Convert Update request to Entity for partial update */
    public static DocumentUnitEntity toEntity(UpdateDocumentUnitRequest request, String userId) {
        if (request == null) {
            return null;
        }
        DocumentUnitEntity entity = new DocumentUnitEntity();
        entity.setId(request.getDocumentUnitId());
        entity.setContent(request.getContent());
        // 如果需要重新向量化，设置向量化状态为false
        if (Boolean.TRUE.equals(request.getReEmbedding())) {
            entity.setIsVector(false);
        }
        return entity;
    }

    /** Convert DTO to Entity */
    public static DocumentUnitEntity toEntity(DocumentUnitDTO dto) {
        if (dto == null) {
            return null;
        }
        DocumentUnitEntity entity = new DocumentUnitEntity();
        BeanUtils.copyProperties(dto, entity);
        return entity;
    }

    /** Convert DTO list to Entity list */
    public static List<DocumentUnitEntity> toEntities(List<DocumentUnitDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return Collections.emptyList();
        }
        return dtos.stream().map(DocumentUnitAssembler::toEntity).collect(Collectors.toList());
    }

    /** Convert Entity list to DTO list */
    public static List<DocumentUnitDTO> toDTOs(List<DocumentUnitEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        return entities.stream().map(DocumentUnitAssembler::toDTO).collect(Collectors.toList());
    }
}