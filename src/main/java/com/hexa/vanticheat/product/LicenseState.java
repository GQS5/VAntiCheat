package com.hexa.vanticheat.product;

/** Isolated licensing surface (§33). Core detection never branches on license internals. */
public enum LicenseState { UNLICENSED, TRIAL, LICENSED, EXPIRED }

