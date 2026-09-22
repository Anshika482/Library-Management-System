package com.library.lms.dto;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Turning a digital resource on or off for members. */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class DigitalResourceStatusRequest {

    @NotNull(message = "enabled is required: true or false")
    private Boolean enabled;
}
