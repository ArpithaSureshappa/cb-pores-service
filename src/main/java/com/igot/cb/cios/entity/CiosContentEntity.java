package com.igot.cb.cios.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import java.io.Serializable;
import java.sql.Timestamp;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class CiosContentEntity implements Serializable {
    @Id
    private String contentId;
    private String externalId;
    @Type(type = "jsonb")
    @Column(columnDefinition = "jsonb")
    private JsonNode ciosData;
    private Boolean isActive;
    private Timestamp createdOn;
    private Timestamp lastUpdatedOn;
    private String partnerId;
}