/** 中国大陆手机号格式。 */
export const PHONE_PATTERN = /^1[3-9]\d{9}$/

/** 过滤手机号输入中的非数字字符，并限制为 11 位。 */
export const normalizePhone = (value: string) => value.replace(/\D/g, '').slice(0, 11)

/** 判断手机号是否为 11 位中国大陆手机号。 */
export const isValidPhone = (value: string) => PHONE_PATTERN.test(value)
